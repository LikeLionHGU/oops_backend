package com.example.oops.analyzer;

import java.util.List;

/** Stage counters and bounded traces; NO_CANDIDATE is not a safety or accuracy verdict. */
public record CandidateReviewDiagnostics(String revision, int discoveryCalls, int verificationCalls,
        int exploredSegments, int proposed, int duplicates, int invalidProposals,
        int rejected, int uncertain, int verificationFailed, int budgetSkipped, int limitedBatches, int truncatedBatches,
        int expandedCandidates, int verificationLimitedCandidates,
        boolean truncated, List<Trace> candidates, List<VisualContextReviewer.Trace> visualCandidates,
        List<ReviewCaseLibrary.Trace> caseRetrieval, ReviewGuidelineLibrary.Trace guidelineReference,
        List<ReviewGuidelineLibrary.SelectionTrace> contextSelections, int repairCalls, List<RepairTrace> repairs,
        int discoveryRepairCalls) {
    public CandidateReviewDiagnostics {
        candidates = List.copyOf(candidates); visualCandidates = List.copyOf(visualCandidates);
        caseRetrieval = List.copyOf(caseRetrieval);
        contextSelections = List.copyOf(contextSelections);
        repairs = List.copyOf(repairs);
    }
    public record RepairTrace(String candidateId, String initialFailureCode, String state, String finalFailureCode) {}
    public record Trace(String candidateId, String anchorId, String axis, String state,
                        Long contextStartMs, Long contextEndMs, List<String> contextSegmentIds,
                        boolean contextExpanded, boolean contextLimited, String failureCode) {
        public Trace(String id, String anchor, String axis, String state) {
            this(id, anchor, axis, state, null, null, List.of(), false, false, null);
        }
        public Trace { contextSegmentIds = List.copyOf(contextSegmentIds); }
    }
}
