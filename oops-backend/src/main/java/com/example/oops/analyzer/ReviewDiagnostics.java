package com.example.oops.analyzer;

import com.example.oops.domain.*;
import java.util.*;
import static com.example.oops.analyzer.ReviewEvaluation.*;

/** Bounded diagnostics, not raw model output and not a quality score. */
public record ReviewDiagnostics(String evaluatorId, AnalyzerStatus status, int totalSegments,
                                boolean truncated, Map<Failure, Integer> failureCounts, List<SegmentTrace> segments,
                                DialogueReview.Diagnostics dialogue) {
    public ReviewDiagnostics(String evaluatorId, AnalyzerStatus status, int totalSegments, boolean truncated,
                             Map<Failure, Integer> failureCounts, List<SegmentTrace> segments) {
        this(evaluatorId, status, totalSegments, truncated, failureCounts, segments, null);
    }
    ReviewDiagnostics withDialogue(DialogueReview.Diagnostics dialogue) {
        return new ReviewDiagnostics(evaluatorId, status, totalSegments, truncated, failureCounts, segments, dialogue);
    }
    public ReviewDiagnostics { failureCounts = Map.copyOf(failureCounts); segments = List.copyOf(segments); }
    public enum State { PASS, REVIEW_REQUIRED, UNCERTAIN, REJECTED, CONFLICT, NOT_RETURNED, CALL_FAILED, NOT_SELECTED }
    public enum Failure {
        UNKNOWN_ANCHOR, INVALID_DECISION, MISSING_EVIDENCE, TOO_MANY_EVIDENCE,
        UNKNOWN_EVIDENCE_ID, EVIDENCE_OUTSIDE_WINDOW, MISSING_QUOTE, QUOTE_NOT_IN_RAW,
        INVALID_ROLE, PRIMARY_NOT_ANCHOR, DUPLICATE_EVIDENCE, MISSING_PRIMARY,
        PRIMARY_QUOTE_MISMATCH, INVALID_TARGET_TYPE, INVALID_TARGET_RELATION,
        VAGUE_TARGET_REASON, MISSING_TARGET_EVIDENCE, INVALID_MISSING_INFORMATION,
        EVIDENCE_VALIDATION, INVALID_CATEGORY, VAGUE_REASON, NONFINITE_SCORE,
        TARGET_TOO_LONG, MISSING_TARGET
    }
    public record EvidenceLink(String segmentId, EvidenceRole role) {}
    public record DecisionTrace(Decision decision, String reason, String category, String target,
                                List<String> missingInformation, List<EvidenceLink> evidence, boolean truncated) {
        public DecisionTrace { missingInformation = List.copyOf(missingInformation); evidence = List.copyOf(evidence); }
    }
    public record Rejection(String returnedDecision, Failure failure, boolean repair) {}
    public record SegmentTrace(String segmentId, TimelineEventType type, long startMs, long endMs, State state,
                               List<DecisionTrace> decisions, List<Rejection> rejections, int rejectedAttempts,
                               boolean truncated) {
        public SegmentTrace { decisions = List.copyOf(decisions); rejections = List.copyOf(rejections); }
    }

    static final class Collector {
        private static final int MAX_SEGMENTS = 400;
        private static final int MAX_DETAILS = 4;
        private final List<ReviewInput.Segment> source;
        private final Map<String, Detail> details = new LinkedHashMap<>();
        private final Map<Failure, Integer> counts = new EnumMap<>(Failure.class);

        Collector(ReviewInput input, TimelineEventType type) {
            source = input.segments().stream().filter(s -> s.type() == type).toList();
            source.stream().limit(MAX_SEGMENTS).forEach(s -> details.put(s.id(), new Detail()));
        }
        void reject(String id, String decision, Failure failure, boolean repair) {
            counts.merge(failure, 1, Integer::sum);
            var detail = details.get(id);
            if (detail == null) return;
            detail.rejected++;
            if (detail.rejections.size() < MAX_DETAILS) {
                // Never retain arbitrary untrusted enum strings or rejected quotes.
                String returned;
                try { returned = Decision.valueOf(decision).name(); }
                catch (IllegalArgumentException | NullPointerException e) { returned = "INVALID"; }
                detail.rejections.add(new Rejection(returned, failure, repair));
            } else detail.truncated = true;
        }
        void failed(List<ReviewInput.Segment> primary) {
            primary.forEach(s -> { var d = details.get(s.id()); if (d != null) d.callFailed = true; });
        }
        void accepted(Observation o) {
            var d = details.get(o.anchorId());
            if (d == null) return;
            if (d.decisions.size() >= MAX_DETAILS) { d.truncated = true; return; }
            var fields = o.details();
            boolean clipped = o.reason().length() > 240 || o.missingInformation().size() > 6
                    || o.missingInformation().stream().anyMatch(s -> s.length() > 120);
            d.decisions.add(new DecisionTrace(o.decision(), clip(o.reason(), 240), fields == null ? null : fields.category(),
                    fields == null ? null : clip(fields.target(), 200),
                    o.missingInformation().stream().limit(6).map(s -> clip(s, 120)).toList(),
                    o.evidence().stream().map(s -> new EvidenceLink(s.segmentId(), s.role())).toList(), clipped));
            d.truncated |= clipped;
        }
        ReviewDiagnostics finish(String evaluator, AnalyzerStatus status, Set<String> assessed,
                                 Set<String> conflicts, Map<String, EnumSet<Decision>> allDecisions) {
            List<SegmentTrace> traces = new ArrayList<>();
            for (var segment : source.stream().limit(MAX_SEGMENTS).toList()) {
                var d = details.get(segment.id());
                State state;
                if (!segment.reviewTarget()) state = State.NOT_SELECTED;
                else if (conflicts.contains(segment.id())) state = State.CONFLICT;
                else if (!assessed.contains(segment.id())) state = d.rejected > 0 ? State.REJECTED
                        : d.callFailed ? State.CALL_FAILED : State.NOT_RETURNED;
                else {
                    var decisions = allDecisions.get(segment.id());
                    state = decisions.contains(Decision.REVIEW_REQUIRED) ? State.REVIEW_REQUIRED
                            : decisions.contains(Decision.UNCERTAIN) ? State.UNCERTAIN : State.PASS;
                }
                traces.add(new SegmentTrace(segment.id(), segment.type(), segment.startMs(), segment.endMs(), state,
                        d.decisions, d.rejections, d.rejected, d.truncated));
            }
            return new ReviewDiagnostics(evaluator, status, source.size(), source.size() > MAX_SEGMENTS
                    || details.values().stream().anyMatch(d -> d.truncated), counts, traces);
        }
        private static String clip(String text, int max) {
            if (text == null || text.length() <= max) return text;
            int end = max - 1;
            if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
            return text.substring(0, end) + "…";
        }
        private static final class Detail {
            final List<DecisionTrace> decisions = new ArrayList<>();
            final List<Rejection> rejections = new ArrayList<>();
            int rejected;
            boolean callFailed, truncated;
        }
    }
}
