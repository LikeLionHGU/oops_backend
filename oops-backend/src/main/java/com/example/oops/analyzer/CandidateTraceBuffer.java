package com.example.oops.analyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Keeps selected-candidate outcomes ahead of lower-priority diagnostics within a bounded trace list. */
final class CandidateTraceBuffer {
    private final int limit;
    private final List<CandidateReviewDiagnostics.Trace> outcomes = new ArrayList<>();
    private final List<CandidateReviewDiagnostics.Trace> auxiliary = new ArrayList<>();
    private final List<CandidateReviewDiagnostics.Trace> budgetSkipped = new ArrayList<>();
    private int totalCount;

    CandidateTraceBuffer(int limit) {
        if (limit < 0) throw new IllegalArgumentException("Trace limit must be nonnegative");
        this.limit = limit;
    }

    void outcome(CandidateReviewDiagnostics.Trace trace) {
        append(outcomes, trace);
    }

    void auxiliary(CandidateReviewDiagnostics.Trace trace) {
        append(auxiliary, trace);
    }

    void budgetSkipped(CandidateReviewDiagnostics.Trace trace) {
        append(budgetSkipped, trace);
    }

    int totalCount() {
        return totalCount;
    }

    boolean truncated() {
        return totalCount > limit;
    }

    /** Priority is intentional: final outcomes must not be evicted by skip/discovery noise. */
    List<CandidateReviewDiagnostics.Trace> snapshot() {
        List<CandidateReviewDiagnostics.Trace> result = new ArrayList<>(Math.min(limit, totalCount));
        appendUntilFull(result, outcomes);
        appendUntilFull(result, auxiliary);
        appendUntilFull(result, budgetSkipped);
        return List.copyOf(result);
    }

    private void append(List<CandidateReviewDiagnostics.Trace> bucket, CandidateReviewDiagnostics.Trace trace) {
        Objects.requireNonNull(trace);
        totalCount++;
        if (bucket.size() < limit) bucket.add(trace);
    }

    private void appendUntilFull(List<CandidateReviewDiagnostics.Trace> destination,
                                 List<CandidateReviewDiagnostics.Trace> source) {
        int remaining = limit - destination.size();
        if (remaining > 0) destination.addAll(source.subList(0, Math.min(remaining, source.size())));
    }
}
