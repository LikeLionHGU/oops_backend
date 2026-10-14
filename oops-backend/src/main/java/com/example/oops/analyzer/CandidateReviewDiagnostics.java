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
        int discoveryRepairCalls, List<EvidenceCountTrace> evidenceCounts) {
    public CandidateReviewDiagnostics {
        candidates = List.copyOf(candidates); visualCandidates = List.copyOf(visualCandidates);
        caseRetrieval = List.copyOf(caseRetrieval);
        contextSelections = List.copyOf(contextSelections);
        repairs = List.copyOf(repairs);
        evidenceCounts = evidenceCounts == null ? List.of() : List.copyOf(evidenceCounts);
    }
    public record EvidenceCountTrace(String candidateId, String attempt, Integer evidenceCount,
                                     int uniqueSegments, int uniqueSegmentRoles, int duplicateRoleEntries,
                                     int invalidEntries, int configuredLimit, String validationFailure,
                                     TargetContractTrace targetContract) {
        public EvidenceCountTrace(String id, String attempt, Integer count, int segments, int roles,
                                  int duplicates, int invalid, int limit, String failure) {
            this(id, attempt, count, segments, roles, duplicates, invalid, limit, failure, null);
        }
    }
    /** Field/role checks, not a semantic validation of the target or a stored raw response. */
    public record TargetContractTrace(String failureDetail, boolean explicitMentionProvided,
                                     boolean targetNameValid, boolean mentionValid, boolean targetReasonValid,
                                     int targetRoleCount, int matchingTargetRoleCount, int separateContextCount) {}
    public record RepairTrace(String candidateId, String initialFailureCode, String state, String finalFailureCode,
                              String initialDecision, String repairedDecision, Boolean decisionChanged) {
        public RepairTrace(String id, String initial, String state, String failure) {
            this(id, initial, state, failure, null, null, null);
        }
    }
    /** No transcript, hypothesis text, or reference content is persisted in this metadata. */
    public record InputSnapshot(String discoveryRequestKey, String rawSha256, String referenceSha256,
                                String proposalSha256) {}
    public record Trace(String candidateId, String anchorId, String axis, String state,
                        Long contextStartMs, Long contextEndMs, List<String> contextSegmentIds,
                        boolean contextExpanded, boolean contextLimited, String failureCode,
                        Integer proposedEvidenceCount, Integer outOfWindowCount, Boolean anchorQuotePresent,
                        InputSnapshot inputSnapshot) {
        public Trace(String id, String anchor, String axis, String state, Long start, Long end,
                     List<String> ids, boolean expanded, boolean limited, String failure,
                     Integer evidenceCount, Integer outOfWindow, Boolean anchorPresent) {
            this(id, anchor, axis, state, start, end, ids, expanded, limited, failure,
                    evidenceCount, outOfWindow, anchorPresent, null);
        }
        public Trace(String id, String anchor, String axis, String state) {
            this(id, anchor, axis, state, null, null, List.of(), false, false, null, null, null, null);
        }
        public Trace(String candidateId, String anchorId, String axis, String state,
                     Long contextStartMs, Long contextEndMs, List<String> contextSegmentIds,
                     boolean contextExpanded, boolean contextLimited, String failureCode) {
            this(candidateId, anchorId, axis, state, contextStartMs, contextEndMs, contextSegmentIds,
                    contextExpanded, contextLimited, failureCode, null, null, null);
        }
        public Trace { contextSegmentIds = List.copyOf(contextSegmentIds); }
    }
}
