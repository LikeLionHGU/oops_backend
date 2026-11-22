package com.example.oops.analyzer;

import java.util.*;

/**
 * Selected context IDs in transcript order, excluding the judged segment.
 * BUDGET_INFEASIBLE keeps the full required set; it is reported apart, never trimmed to fit.
 */
record ContextSelection(List<String> contextSegmentIds, String selectorId, Status status) {
    enum Status { SELECTED, BUDGET_INFEASIBLE }

    ContextSelection {
        contextSegmentIds = List.copyOf(contextSegmentIds);
        if (selectorId == null || selectorId.isBlank()) throw new IllegalArgumentException("Selector ID required");
        Objects.requireNonNull(status);
    }

    ContextSelection(List<String> contextSegmentIds, String selectorId) {
        this(contextSegmentIds, selectorId, Status.SELECTED);
    }

    /** Validates the contract against the frozen transcript and builds the engine batch. */
    CandidateReviewEngine.ExperimentalBatch toBatch(ReviewInput.Segment target, ReviewInput transcript) {
        if (transcript.find(target.id()).isEmpty()) throw new IllegalArgumentException("Target outside transcript: " + target.id());
        Set<String> seen = new HashSet<>();
        ReviewInput.Segment previous = null;
        for (String id : contextSegmentIds) {
            if (id.equals(target.id())) throw new IllegalArgumentException("Context must exclude the target: " + id);
            if (!seen.add(id)) throw new IllegalArgumentException("Duplicate context ID: " + id);
            var segment = transcript.find(id).orElseThrow(() -> new IllegalArgumentException("Context outside transcript: " + id));
            if (previous != null && transcript.segments().indexOf(segment) < transcript.segments().indexOf(previous))
                throw new IllegalArgumentException("Context IDs must follow transcript order: " + id);
            previous = segment;
        }
        return new CandidateReviewEngine.ExperimentalBatch(List.of(target.id()), contextSegmentIds);
    }
}
