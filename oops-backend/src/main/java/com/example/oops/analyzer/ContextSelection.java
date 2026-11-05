package com.example.oops.analyzer;

import java.util.*;

/** Selected context IDs in transcript order, excluding the judged segment. */
record ContextSelection(List<String> contextSegmentIds, String selectorId) {
    ContextSelection {
        contextSegmentIds = List.copyOf(contextSegmentIds);
        if (selectorId == null || selectorId.isBlank()) throw new IllegalArgumentException("Selector ID required");
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
