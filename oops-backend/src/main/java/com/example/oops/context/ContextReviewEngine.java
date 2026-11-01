package com.example.oops.context;

import com.example.oops.context.ContextModelOutput.Discovery;
import com.example.oops.context.ContextModelOutput.DiscoveryCandidate;
import com.example.oops.context.ContextModelOutput.Evidence;
import com.example.oops.context.ContextModelOutput.Gate;
import com.example.oops.context.ContextModelOutput.GateResult;
import com.example.oops.context.ContextModelOutput.MissingInformation;
import com.example.oops.context.ContextModelOutput.Review;
import com.example.oops.context.ContextModelOutput.ReviewResult;
import com.example.oops.context.ContextPrompts.Item;
import com.example.oops.context.LineContextBuilder.ContextLine;
import com.example.oops.context.LineContextBuilder.ContextWindow;
import com.example.oops.context.criteria.CriteriaModel.CriteriaSnapshot;
import com.example.oops.context.criteria.CriteriaModel.CriterionBundle;
import com.example.oops.context.criteria.CriteriaModel.StopJudgment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static com.example.oops.context.ContextModelOutput.list;

/**
 * 맥락 확인 3단계 실행과 서버 검증. (redesign-v3, docs/redesign-v3.md 3절)
 *
 *   1. 후보 탐색: 대상 묶음마다 21개 공통 기준 본문 전체로 후보(기준 ID + 핵심 구간)를 받는다.
 *   2. 독립 점검: 같은 기준 후보끼리 묶어 진행·제외·중단을 받는다. 추가 기준은 넣지 않는다.
 *      대본에서 나올 수 있는 정보가 부족할 때만 문맥을 한 번 확대해 다시 점검한다.
 *   3. 상세 검토: 진행 후보만, 추가 기준 전부 + 허용·중단 판단 범위로 검토한다.
 *
 * AI 출력은 모두 검증한다: 구간 ID 가 실제 제공한 구간인지, 인용이 그 구간 원문에 있는지,
 * 중단 판단 코드가 그 기준의 코드인지, 상세 검토가 중단·단정 금지 판단을 다시 썼는지.
 * 검증에 걸리면 결과에 쓰지 않고 내부 기록(trace)에 INVALID 로 남긴다.
 *
 * 호출 실패는 "정보 부족"과 다르게 FAILED 로 센다. 호출하는 쪽이 PARTIAL·FAILED 로 알린다.
 *
 * 순서대로 호출한다. OpenAiClient 의 사용량·실패 집계가 스레드 단위(ThreadLocal)라서,
 * 병렬로 바꾸려면 그 집계를 실행 단위 객체로 옮기는 작업이 먼저 필요하다(docs 5절).
 */
public final class ContextReviewEngine {

    /** AI 호출. 실패하면 Optional.empty() */
    @FunctionalInterface
    public interface Model {
        <T> Optional<T> call(String system, String user, Class<T> type);
    }

    public enum Status { PROCEED, EXCLUDE, STOP }

    public enum Decision { REVIEW, EXCLUDE }

    /** 내부 기록 한 줄. 사용자에게 보여주지 않는다. */
    public record TraceEntry(String stage, String candidateId, String criterionId, String gateId,
                             List<String> coreSegmentIds, String outcome, String reason,
                             List<String> allowedJudgments, List<String> stoppedJudgments,
                             List<String> missingInformation, boolean expanded, String note) {}

    public record AcceptedEvidence(String segmentId, long startMs, long endMs, String quote, String role) {}

    /** 사용자 결과로 내보낼 수 있는 검토 결과. */
    public record Accepted(String candidateId, CriterionBundle criterion, List<ContextLine> core, String reason,
                           String interpretationLimit, List<AcceptedEvidence> evidence,
                           List<String> allowedJudgments, List<String> stoppedJudgments, boolean expanded) {}

    public record Result(String criteriaVersion, List<Accepted> accepted, List<TraceEntry> trace, int modelCalls,
                         int failedCalls, int skippedCandidates, int invalidOutputs) {
        public boolean partial() {
            return failedCalls > 0 || skippedCandidates > 0;
        }
    }

    private final ContextProperties p;
    private final LineContextBuilder contexts;
    private final Model model;

    public ContextReviewEngine(ContextProperties properties, Model model) {
        this.p = properties;
        this.contexts = new LineContextBuilder(properties);
        this.model = model;
    }

    private static final class Candidate {
        final String id;
        final CriterionBundle criterion;
        final List<String> coreIds;
        final String discoveryReason;
        ContextWindow window;
        List<String> allowed = List.of();
        List<StopJudgment> stopped = List.of();

        Candidate(String id, CriterionBundle criterion, List<String> coreIds, String reason) {
            this.id = id;
            this.criterion = criterion;
            this.coreIds = coreIds;
            this.discoveryReason = reason;
        }
    }

    private final class Run {
        final List<ContextLine> lines;
        final CriteriaSnapshot criteria;
        final Runnable checkActive;
        final List<TraceEntry> trace = new ArrayList<>();
        final List<Accepted> accepted = new ArrayList<>();
        int calls, failed, skipped, invalid;

        Run(List<ContextLine> lines, CriteriaSnapshot criteria, Runnable checkActive) {
            this.lines = lines;
            this.criteria = criteria;
            this.checkActive = checkActive;
        }

        <T> Optional<T> call(ContextPrompts.Prompt prompt, Class<T> type) {
            checkActive.run();
            calls++;
            Optional<T> out = model.call(prompt.system(), prompt.user(), type);
            if (out.isEmpty()) failed++;
            return out;
        }

        void trace(String stage, Candidate c, String outcome, String reason, List<String> missing, String note) {
            trace.add(new TraceEntry(stage, c == null ? null : c.id, c == null ? null : c.criterion.id(),
                    c == null ? null : c.criterion.gate().id(), c == null ? List.of() : c.coreIds, outcome, reason,
                    c == null ? List.of() : c.allowed,
                    c == null ? List.of() : c.stopped.stream().map(StopJudgment::code).toList(),
                    missing == null ? List.of() : missing, c != null && c.window != null && c.window.expanded(), note));
        }
    }

    public Result run(List<ContextLine> lines, CriteriaSnapshot criteria, Runnable checkActive) {
        Run run = new Run(List.copyOf(lines), criteria, checkActive == null ? () -> { } : checkActive);
        if (lines.isEmpty()) return result(run);

        List<Candidate> candidates = discover(run);
        if (candidates.size() > p.maxCandidates()) {
            run.skipped = candidates.size() - p.maxCandidates();
            candidates.subList(p.maxCandidates(), candidates.size())
                    .forEach(c -> run.trace("DISCOVERY", c, "SKIPPED_BUDGET", c.discoveryReason, null,
                            "영상당 후보 상한(" + p.maxCandidates() + ") 초과"));
            candidates = new ArrayList<>(candidates.subList(0, p.maxCandidates()));
        }

        List<Candidate> proceed = new ArrayList<>();
        List<Candidate> expand = new ArrayList<>();
        for (var batch : batchesByCriterion(candidates)) {
            for (Candidate c : batch) c.window = contexts.aroundIds(run.lines, c.coreIds, false).orElseThrow();
            gate(run, batch, false, proceed, expand);
        }
        for (var batch : batchesByCriterion(expand)) {
            for (Candidate c : batch) c.window = contexts.aroundIds(run.lines, c.coreIds, true).orElseThrow();
            gate(run, batch, true, proceed, null);
        }
        for (var batch : batchesByCriterion(proceed)) review(run, batch);
        return result(run);
    }

    private Result result(Run run) {
        return new Result(run.criteria.versionLabel(), List.copyOf(run.accepted), List.copyOf(run.trace), run.calls,
                run.failed, run.skipped, run.invalid);
    }

    // ── 1. 후보 탐색 ──────────────────────────────────────────────────

    private List<Candidate> discover(Run run) {
        Map<String, Candidate> byKey = new LinkedHashMap<>();
        Map<String, CriterionBundle> criteria = run.criteria.byId();
        int seq = 0;
        for (ContextWindow window : contexts.discoveryWindows(run.lines)) {
            var out = run.call(ContextPrompts.discovery(run.criteria, window), Discovery.class);
            String range = window.core().get(0).id() + "~" + window.core().get(window.core().size() - 1).id();
            if (out.isEmpty()) {
                run.trace("DISCOVERY", null, "FAILED", null, null, "후보 탐색 호출 실패: " + range);
                continue;
            }
            Set<String> checked = new HashSet<>(list(out.get().checkedCriterionIds()));
            if (!checked.containsAll(criteria.keySet())) {
                Set<String> missing = new TreeSet<>(criteria.keySet());
                missing.removeAll(checked);
                run.trace("DISCOVERY", null, "UNCHECKED_CRITERIA", null, List.copyOf(missing), range);
            }
            Set<String> coreIds = window.coreIds();
            for (DiscoveryCandidate d : list(out.get().candidates())) {
                CriterionBundle criterion = d.criterionId() == null ? null : criteria.get(d.criterionId());
                List<String> ids = list(d.coreSegmentIds());
                if (criterion == null || ids.isEmpty() || !coreIds.containsAll(ids) || blank(d.reason())) {
                    run.invalid++;
                    run.trace.add(new TraceEntry("DISCOVERY", null, d.criterionId(), null, ids, "INVALID", d.reason(),
                            List.of(), List.of(), List.of(), false,
                            criterion == null ? "없는 기준 ID" : "핵심 구간이 검토 대상 밖이거나 비어 있음"));
                    continue;
                }
                List<String> ordered = run.lines.stream().map(ContextLine::id).filter(new HashSet<>(ids)::contains).toList();
                String key = criterion.id() + "|" + String.join(",", ordered);
                if (!byKey.containsKey(key)) {
                    byKey.put(key, new Candidate("c" + (++seq), criterion, ordered, d.reason().strip()));
                }
            }
        }
        byKey.values().forEach(c -> run.trace("DISCOVERY", c, "CANDIDATE", c.discoveryReason, null, null));
        return new ArrayList<>(byKey.values());
    }

    // ── 2. 독립 점검 ──────────────────────────────────────────────────

    private void gate(Run run, List<Candidate> batch, boolean expanded, List<Candidate> proceed, List<Candidate> expand) {
        String stage = expanded ? "GATE_EXPANDED" : "GATE";
        CriterionBundle criterion = batch.get(0).criterion;
        var items = batch.stream().map(c -> new Item(c.id, c.window, List.of(), List.of())).toList();
        var out = run.call(ContextPrompts.gate(criterion, items), Gate.class);
        if (out.isEmpty()) {
            batch.forEach(c -> run.trace(stage, c, "FAILED", null, null, "독립 점검 호출 실패"));
            return;
        }
        Map<String, GateResult> byId = new HashMap<>();
        for (GateResult r : list(out.get().results())) if (r.candidateId() != null) byId.putIfAbsent(r.candidateId(), r);

        for (Candidate c : batch) {
            GateResult r = byId.get(c.id);
            if (r == null) {
                run.invalid++;
                run.trace(stage, c, "INVALID", null, null, "결과 누락");
                continue;
            }
            List<String> missing = list(r.missingInformation()).stream().map(MissingInformation::what)
                    .filter(w -> !blank(w)).toList();
            Optional<String> problem = gateProblem(c, r);
            if (problem.isPresent()) {
                run.invalid++;
                run.trace(stage, c, "INVALID", r.reason(), missing, problem.get());
                continue;
            }
            Status status = Status.valueOf(r.status().strip());
            if (status == Status.PROCEED) {
                c.allowed = list(r.allowedJudgments()).stream().filter(s -> !blank(s)).map(String::strip).toList();
                c.stopped = list(r.stoppedJudgments()).stream().map(String::strip).distinct()
                        .map(code -> c.criterion.judgment(code).orElseThrow()).toList();
                run.trace(stage, c, "PROCEED", r.reason(), missing, null);
                proceed.add(c);
            } else if (status == Status.STOP && expand != null && Boolean.TRUE.equals(r.needsExpansion())
                    && list(r.missingInformation()).stream().anyMatch(m -> Boolean.TRUE.equals(m.availableInTranscript()))) {
                run.trace(stage, c, "EXPAND", r.reason(), missing, "대본에서 나올 수 있는 정보가 부족해 1회 확대");
                expand.add(c);
            } else {
                run.trace(stage, c, status.name(), r.reason(), missing,
                        status == Status.STOP && Boolean.TRUE.equals(r.needsExpansion())
                                ? (expanded ? "확대 후에도 부족" : "대본 밖 정보라 확대하지 않음") : null);
            }
        }
    }

    private Optional<String> gateProblem(Candidate c, GateResult r) {
        Status status;
        try {
            status = Status.valueOf(String.valueOf(r.status()).strip());
        } catch (IllegalArgumentException e) {
            return Optional.of("알 수 없는 상태: " + r.status());
        }
        if (blank(r.reason())) return Optional.of("이유 없음");
        Optional<String> evidence = evidenceProblem(c.window, list(r.evidence()));
        if (evidence.isPresent()) return evidence;
        Set<String> allowedCodes = c.criterion.stopIfMissingCodes();
        for (String code : list(r.stoppedJudgments())) {
            if (!allowedCodes.contains(code.strip())) return Optional.of("이 기준에 없는 중단 판단 코드: " + code);
        }
        if (status == Status.PROCEED) {
            if (list(r.evidence()).isEmpty()) return Optional.of("진행인데 근거 인용 없음");
            if (list(r.allowedJudgments()).stream().allMatch(ContextReviewEngine::blank)) return Optional.of("진행인데 허용 판단 없음");
        }
        return Optional.empty();
    }

    // ── 3. 상세 검토 ──────────────────────────────────────────────────

    private void review(Run run, List<Candidate> batch) {
        CriterionBundle criterion = batch.get(0).criterion;
        var items = batch.stream().map(c -> new Item(c.id, c.window, c.allowed, c.stopped)).toList();
        var out = run.call(ContextPrompts.review(criterion, items), Review.class);
        if (out.isEmpty()) {
            batch.forEach(c -> run.trace("REVIEW", c, "FAILED", null, null, "상세 검토 호출 실패"));
            return;
        }
        Map<String, ReviewResult> byId = new HashMap<>();
        for (ReviewResult r : list(out.get().reviews())) if (r.candidateId() != null) byId.putIfAbsent(r.candidateId(), r);

        Set<String> additionalIds = new LinkedHashSet<>();
        criterion.additional().forEach(a -> additionalIds.add(a.id()));
        for (Candidate c : batch) {
            ReviewResult r = byId.get(c.id);
            if (r == null) {
                run.invalid++;
                run.trace("REVIEW", c, "INVALID", null, null, "결과 누락");
                continue;
            }
            List<String> infoMissing = list(r.additionalReviews()).stream()
                    .filter(a -> "INFO_MISSING".equals(a.effect())).map(a -> a.additionalId() + ": " + a.finding()).toList();
            Optional<String> problem = reviewProblem(c, r);
            if (problem.isPresent()) {
                run.invalid++;
                run.trace("REVIEW", c, "INVALID", r.reason(), infoMissing, problem.get());
                continue;
            }
            Set<String> covered = new HashSet<>();
            list(r.additionalReviews()).forEach(a -> covered.add(a.additionalId()));
            Set<String> uncovered = new TreeSet<>(additionalIds);
            uncovered.removeAll(covered);
            String note = uncovered.isEmpty() ? null : "추가 기준 응답 누락: " + uncovered;

            if (Decision.valueOf(r.decision().strip()) == Decision.EXCLUDE) {
                run.trace("REVIEW", c, "EXCLUDE", r.reason(), infoMissing, note);
                continue;
            }
            List<AcceptedEvidence> evidence = list(r.evidence()).stream().map(e -> {
                ContextLine line = c.window.line(e.segmentId()).orElseThrow();
                String role = c.window.coreIds().contains(line.id()) ? "PRIMARY" : "CONTEXT";
                return new AcceptedEvidence(line.id(), line.startMs() == null ? 0 : line.startMs(),
                        line.endMs() == null ? 0 : line.endMs(), e.quote().strip(), role);
            }).toList();
            List<ContextLine> core = c.window.core();
            run.accepted.add(new Accepted(c.id, criterion, core, r.reason().strip(),
                    blank(r.interpretationLimit()) ? null : r.interpretationLimit().strip(), evidence,
                    c.allowed, c.stopped.stream().map(StopJudgment::code).toList(), c.window.expanded()));
            run.trace("REVIEW", c, "REVIEW", r.reason(), infoMissing, note);
        }
    }

    private Optional<String> reviewProblem(Candidate c, ReviewResult r) {
        Decision decision;
        try {
            decision = Decision.valueOf(String.valueOf(r.decision()).strip());
        } catch (IllegalArgumentException e) {
            return Optional.of("알 수 없는 결정: " + r.decision());
        }
        if (blank(r.reason())) return Optional.of("이유 없음");
        Optional<String> evidence = evidenceProblem(c.window, list(r.evidence()));
        if (evidence.isPresent()) return evidence;
        if (decision == Decision.EXCLUDE) return Optional.empty();
        if (list(r.evidence()).isEmpty()) return Optional.of("검토인데 근거 인용 없음");

        Set<String> forbidden = new HashSet<>(c.criterion.neverAssertCodes());
        c.stopped.forEach(j -> forbidden.add(j.code()));
        Set<String> known = new HashSet<>();
        c.criterion.judgments().forEach(j -> known.add(j.code()));
        for (String code : list(r.usedJudgments())) {
            String k = code.strip();
            if (forbidden.contains(k)) return Optional.of("중단·단정 금지 판단을 사용: " + k);
            if (!known.contains(k)) return Optional.of("이 기준에 없는 판단 코드: " + k);
        }
        String reason = r.reason();
        for (StopJudgment j : c.stopped) {
            if (reason.contains(j.label())) return Optional.of("중단된 판단을 이유에 씀: " + j.code());
        }
        return Optional.empty();
    }

    /** 인용이 제공한 구간의 원문에 실제로 있는지. 공백 차이만 허용한다. */
    private static Optional<String> evidenceProblem(ContextWindow window, List<Evidence> evidence) {
        for (Evidence e : evidence) {
            if (e.segmentId() == null) return Optional.of("근거 구간 ID 없음");
            Optional<ContextLine> line = window.line(e.segmentId().strip());
            if (line.isEmpty()) return Optional.of("제공하지 않은 구간 ID: " + e.segmentId());
            if (blank(e.quote()) || !squash(line.get().text()).contains(squash(e.quote())))
                return Optional.of("원문에 없는 인용: " + e.segmentId());
        }
        return Optional.empty();
    }

    private List<List<Candidate>> batchesByCriterion(List<Candidate> candidates) {
        Map<String, List<Candidate>> byCriterion = new LinkedHashMap<>();
        for (Candidate c : candidates) byCriterion.computeIfAbsent(c.criterion.id(), k -> new ArrayList<>()).add(c);
        List<List<Candidate>> batches = new ArrayList<>();
        for (var group : byCriterion.values()) {
            for (int i = 0; i < group.size(); i += p.batchSize()) {
                batches.add(group.subList(i, Math.min(group.size(), i + p.batchSize())));
            }
        }
        return batches;
    }

    private static String squash(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
