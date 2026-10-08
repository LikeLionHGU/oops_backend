package com.example.oops.analyzer;

import java.util.List;

/** Internal evaluator contract; not a persisted finding or an API response. */
public record ReviewEvaluation(String evaluatorId, ExecutionStatus status,
                               List<String> reviewedSegmentIds, List<Observation> observations) {
    public ReviewEvaluation {
        java.util.Objects.requireNonNull(status, "status");
        reviewedSegmentIds = List.copyOf(reviewedSegmentIds);
        observations = List.copyOf(observations);
    }

    public enum ExecutionStatus { SUCCESS, FAILED, NOT_ASSESSED }
    public enum Decision { PASS, REVIEW_REQUIRED, UNCERTAIN }

    public record Observation(String anchorId, Decision decision, String reason,
                              List<EvidenceSpan> evidence, List<String> missingInformation, Details details) {
        public Observation(String anchorId, Decision decision, String reason,
                           List<EvidenceSpan> evidence, List<String> missingInformation) {
            this(anchorId, decision, reason, evidence, missingInformation, null);
        }
        public Observation {
            java.util.Objects.requireNonNull(decision, "decision");
            evidence = List.copyOf(evidence);
            missingInformation = List.copyOf(missingInformation);
        }
    }

    /** Inferences are never substituted for raw evidence. */
    public record Details(String category, String target, Double score, String context, String reading) {}

    /** Unicode code-point offsets in unchanged raw text; end is exclusive. */
    public record EvidenceSpan(String segmentId, String quote, int start, int end) {}
}
