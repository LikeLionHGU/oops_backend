package com.example.oops.analyzer;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import static com.example.oops.analyzer.ReviewEvaluation.*;
import static com.example.oops.analyzer.ReviewDiagnostics.*;

/** Explicit window-scope assessment, separate from segment-scope decisions. */
public final class DialogueReview {
    private DialogueReview() {}
    public enum Relation { SAME_TARGET_CONNECTED, NO_CONNECTED_EVALUATION, CONNECTED_EXPRESSION, INSUFFICIENT_CONTEXT }
    public record Unit(String unitId, List<String> segmentIds, List<String> primarySegmentIds,
                       long startMs, long endMs, boolean limited) {}
    public record LlmDecision(String unitId, String relation, TextReviewEngine.LlmDecision assessment) {}
    /** Controlled shape only: never retain rejected quotes, target names, reasons or unknown IDs. */
    public record RejectedShape(String decision, String relation, String category,
                                boolean targetPresent, int outsideEvidenceCount) {}
    public record Trace(String unitId, List<String> segmentIds, long startMs, long endMs,
                        State state, String relation, String anchorId, String reason,
                        String category, String target, List<String> missingInformation,
                        List<EvidenceLink> evidence, String failure, boolean truncated,
                        RejectedShape rejectedShape) {}
    public record Diagnostics(int requested, int assessed, int invalidAttempts, int conflicts,
                              int uncertain, int unselectedAnchors, boolean truncated, List<Trace> units,
                              String executionMode, int calls, int budgetSkipped) {}
    record Plan(List<Unit> units, int unselectedAnchors) {}

    /** Inline raw evidence scoped to exactly one planned unit; never expands the validator's window. */
    record PromptSegment(String id, long startMs, long endMs, String text, boolean anchorEligible) {}
    record PromptUnit(String unitId, List<String> segmentIds, List<String> primarySegmentIds,
                      long startMs, long endMs, boolean limited, List<PromptSegment> segments) {}

    static List<PromptUnit> promptUnits(TextReviewBatchPlanner.Batch batch, Plan plan) {
        Map<String, ReviewInput.Segment> supplied = new LinkedHashMap<>();
        batch.primary().forEach(s -> supplied.put(s.id(), s));
        batch.context().forEach(s -> supplied.put(s.id(), s));
        Set<String> primary = new HashSet<>();
        batch.primary().forEach(s -> primary.add(s.id()));
        return plan.units().stream().map(u -> {
            if (!supplied.keySet().containsAll(u.segmentIds())
                    || !u.segmentIds().containsAll(u.primarySegmentIds())
                    || !primary.containsAll(u.primarySegmentIds())) {
                throw new IllegalArgumentException("Unit prompt references unavailable segments");
            }
            var segments = u.segmentIds().stream().map(supplied::get)
                    .sorted(Comparator.comparingLong(ReviewInput.Segment::startMs)
                            .thenComparingLong(ReviewInput.Segment::endMs).thenComparing(ReviewInput.Segment::id))
                    .map(s -> new PromptSegment(s.id(), s.startMs(), s.endMs(), s.text(),
                            u.primarySegmentIds().contains(s.id()))).toList();
            return new PromptUnit(u.unitId(), u.segmentIds(), u.primarySegmentIds(),
                    u.startMs(), u.endMs(), u.limited(), segments);
        }).toList();
    }

    static Plan plan(TextReviewBatchPlanner.Batch batch) {
        var candidates = new LinkedHashMap<List<String>, ReviewUnit>();
        ReviewUnit.all(batch).stream().filter(u -> u.segmentIds().size() >= 2)
                .forEach(u -> candidates.putIfAbsent(u.segmentIds(), u));
        Set<String> remaining = new HashSet<>();
        batch.primary().forEach(s -> remaining.add(s.id()));
        List<Unit> selected = new ArrayList<>();
        while (!remaining.isEmpty() && selected.size() < 6) {
            var best = candidates.values().stream().filter(u -> u.segmentIds().stream().anyMatch(remaining::contains))
                    .max(Comparator.comparingLong((ReviewUnit u) -> u.segmentIds().stream().filter(remaining::contains).count())
                            .thenComparingLong(u -> u.endMs() - u.startMs()).thenComparingLong(u -> -u.startMs())).orElse(null);
            if (best == null) break;
            var primary = batch.primary().stream().map(ReviewInput.Segment::id).filter(best.segmentIds()::contains).toList();
            String id = UUID.nameUUIDFromBytes(String.join("\n", best.segmentIds()).getBytes(StandardCharsets.UTF_8)).toString();
            selected.add(new Unit("dialogue-" + id, best.segmentIds(), primary, best.startMs(), best.endMs(), best.limited()));
            remaining.removeAll(primary);
            candidates.remove(best.segmentIds());
        }
        // Isolated utterances are already covered at segment scope, not silently judged as dialogue.
        int omitted = (int) remaining.stream().filter(id -> candidates.values().stream()
                .anyMatch(u -> u.segmentIds().contains(id))).count();
        return new Plan(List.copyOf(selected), omitted);
    }

    static final class Collector {
        private final Map<String, Unit> requested = new LinkedHashMap<>();
        private final Map<String, Observation> accepted = new LinkedHashMap<>();
        private final Map<String, EnumSet<Decision>> decisions = new HashMap<>();
        private final Map<String, Trace> traces = new LinkedHashMap<>();
        private int invalid, unselected;

        void consume(Plan plan, List<LlmDecision> returned, boolean failed,
                     Function<LlmDecision, Observation> validator) {
            unselected += plan.unselectedAnchors();
            Map<String, Unit> current = new LinkedHashMap<>();
            plan.units().forEach(u -> {
                current.put(u.unitId(), u); requested.putIfAbsent(u.unitId(), u);
                traces.putIfAbsent(u.unitId(), trace(u, failed ? State.CALL_FAILED : State.NOT_RETURNED, null, null, null));
            });
            if (failed || returned == null) return;
            Set<String> seen = new HashSet<>();
            for (var item : returned) {
                if (item == null || !current.containsKey(item.unitId())) { invalid++; continue; }
                var unit = current.get(item.unitId());
                try {
                    if (!seen.add(item.unitId())) throw new IllegalArgumentException("DUPLICATE_UNIT");
                    var relation = Relation.valueOf(item.relation());
                    var raw = Objects.requireNonNull(item.assessment());
                    if (!unit.primarySegmentIds().contains(raw.segmentId())) throw new IllegalArgumentException("ANCHOR_NOT_PRIMARY");
                    if (raw.evidence() == null || raw.evidence().isEmpty()) throw new IllegalArgumentException("UNIT_MISSING_EVIDENCE");
                    if (raw.evidence().stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("UNIT_INVALID_EVIDENCE");
                    if (raw.evidence().stream().anyMatch(e -> !unit.segmentIds().contains(e.segmentId()))) throw new IllegalArgumentException("UNIT_EVIDENCE_OUTSIDE_WINDOW");
                    if (raw.evidence().stream().map(TextReviewEngine.LlmEvidence::segmentId).distinct().count() < 2) throw new IllegalArgumentException("UNIT_DISTINCT_EVIDENCE_REQUIRED");
                    var o = validator.apply(item);
                    if (!VagueReasonFilter.isUseful(o.reason())) throw new IllegalArgumentException("VAGUE_REASON");
                    if (relation == Relation.NO_CONNECTED_EVALUATION && o.decision() != Decision.PASS
                            || relation == Relation.INSUFFICIENT_CONTEXT && o.decision() != Decision.UNCERTAIN
                            || (relation == Relation.SAME_TARGET_CONNECTED || relation == Relation.CONNECTED_EXPRESSION)
                               && o.decision() == Decision.UNCERTAIN) {
                        throw new IllegalArgumentException("RELATION_DECISION_MISMATCH");
                    }
                    boolean expressionCandidate = o.decision() == Decision.REVIEW_REQUIRED
                            && "GRAPHIC_METAPHOR".equals(o.details().category());
                    if (relation == Relation.CONNECTED_EXPRESSION && o.decision() == Decision.REVIEW_REQUIRED && !expressionCandidate) {
                        throw new IllegalArgumentException("UNIT_EXPRESSION_CATEGORY_REQUIRED");
                    }
                    if (o.decision() == Decision.REVIEW_REQUIRED && (o.details().target() == null || o.details().target().isBlank())
                            && !(relation == Relation.CONNECTED_EXPRESSION && expressionCandidate)) {
                        throw new IllegalArgumentException("UNIT_TARGET_REQUIRED");
                    }
                    decisions.computeIfAbsent(item.unitId(), ignored -> EnumSet.noneOf(Decision.class)).add(o.decision());
                    accepted.put(item.unitId(), o);
                    traces.put(item.unitId(), trace(unit, State.valueOf(o.decision().name()), relation.name(), o, null));
                } catch (RuntimeException e) {
                    invalid++;
                    // Preserve only allowlisted enums and counts, never rejected free text.
                    if (!accepted.containsKey(item.unitId())) traces.put(item.unitId(), trace(unit, State.REJECTED, null, null, failureCode(e), rejectedShape(unit, item)));
                    if (seen.contains(item.unitId()) && "DUPLICATE_UNIT".equals(e.getMessage())) {
                        decisions.computeIfAbsent(item.unitId(), ignored -> EnumSet.noneOf(Decision.class)).addAll(EnumSet.allOf(Decision.class));
                    }
                }
            }
        }
        Map<String, Observation> publishable() {
            var result = new LinkedHashMap<String, Observation>();
            accepted.forEach((id, o) -> { if (decisions.get(id).size() == 1 && o.decision() == Decision.REVIEW_REQUIRED) result.put(id, o); });
            return result;
        }
        void budgetSkipped(Plan plan) {
            consume(plan, null, false, ignored -> { throw new IllegalStateException(); });
            plan.units().forEach(u -> traces.put(u.unitId(), trace(u, State.NOT_RETURNED, null, null, "UNIT_CALL_BUDGET_EXHAUSTED")));
        }
        Diagnostics finish() {
            return finish("UNSPECIFIED", 0, 0);
        }
        Diagnostics finish(String executionMode, int calls, int budgetSkipped) {
            Set<String> conflicts = new HashSet<>();
            decisions.forEach((id, d) -> { if (d.size() > 1) conflicts.add(id); });
            var result = traces.entrySet().stream().limit(200).map(e -> conflicts.contains(e.getKey())
                    ? trace(requested.get(e.getKey()), State.CONFLICT, null, null, "CONFLICT") : e.getValue()).toList();
            return new Diagnostics(requested.size(), (int) accepted.keySet().stream().filter(id -> !conflicts.contains(id)).count(), invalid, conflicts.size(),
                    (int) accepted.entrySet().stream().filter(e -> !conflicts.contains(e.getKey()) && e.getValue().decision() == Decision.UNCERTAIN).count(),
                    unselected, requested.size() > 200, result, executionMode, calls, budgetSkipped);
        }
        private static Trace trace(Unit u, State state, String relation, Observation o, String failure) {
            return trace(u, state, relation, o, failure, null);
        }
        private static Trace trace(Unit u, State state, String relation, Observation o, String failure, RejectedShape shape) {
            return new Trace(u.unitId(), u.segmentIds(), u.startMs(), u.endMs(), state, relation,
                    o == null ? null : o.anchorId(), o == null ? null : clip(o.reason()),
                    o == null || o.decision() != Decision.REVIEW_REQUIRED ? null : o.details().category(),
                    o == null || o.details() == null ? null : o.details().target(),
                    o == null ? List.of() : o.missingInformation().stream().limit(6).map(Collector::clip).toList(),
                    o == null ? List.of() : o.evidence().stream().map(s -> new EvidenceLink(s.segmentId(), s.role())).toList(), failure,
                    o != null && (o.reason().length() > 240 || o.missingInformation().size() > 6
                            || o.missingInformation().stream().anyMatch(s -> s.length() > 240)), shape);
        }
        private static RejectedShape rejectedShape(Unit unit, LlmDecision item) {
            var raw = item.assessment();
            int outside = raw == null || raw.evidence() == null ? 0 : (int) raw.evidence().stream()
                    .filter(Objects::nonNull).filter(e -> !unit.segmentIds().contains(e.segmentId())).count();
            return new RejectedShape(raw == null ? null : controlled(Decision.class, raw.decision()),
                    controlled(Relation.class, item.relation()),
                    raw == null || raw.category() == null ? null : controlled(com.example.oops.domain.RiskCategory.class, raw.category()),
                    raw != null && raw.target() != null && !raw.target().isBlank(), outside);
        }
        private static <E extends Enum<E>> String controlled(Class<E> type, String value) {
            if (value == null) return "INVALID";
            try { return Enum.valueOf(type, value).name(); }
            catch (IllegalArgumentException ignored) { return "INVALID"; }
        }
        private static String failureCode(RuntimeException e) {
            String code = e.getMessage();
            if (code == null) return "UNIT_CONTRACT_REJECTED";
            if (Set.of("DUPLICATE_UNIT", "ANCHOR_NOT_PRIMARY", "UNIT_MISSING_EVIDENCE", "UNIT_INVALID_EVIDENCE",
                    "UNIT_EVIDENCE_OUTSIDE_WINDOW", "UNIT_DISTINCT_EVIDENCE_REQUIRED", "RELATION_DECISION_MISMATCH",
                    "UNIT_TARGET_REQUIRED", "UNIT_EXPRESSION_CATEGORY_REQUIRED").contains(code)
                    || Arrays.stream(Failure.values()).anyMatch(f -> f.name().equals(code))) return code;
            return "UNIT_CONTRACT_REJECTED";
        }
        private static String clip(String text) {
            if (text.length() <= 240) return text;
            int end = Character.isHighSurrogate(text.charAt(238)) ? 238 : 239;
            return text.substring(0, end) + "…";
        }
    }
}
