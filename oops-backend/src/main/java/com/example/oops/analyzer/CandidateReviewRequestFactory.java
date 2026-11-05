package com.example.oops.analyzer;

import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Pure request/prompt assembly for candidate discovery and verification stages. */
final class CandidateReviewRequestFactory {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CandidateReviewRequestFactory() {}

    record DiscoveryRequest(String system, String input, boolean hasReferences) {}

    static DiscoveryRequest discovery(String revision, boolean sourceRelationsEnabled, String guidelinePrompt,
                                      List<String> primaryIds, List<ReviewInput.Segment> raw,
                                      List<ReviewUnit> units, List<ReviewUnit> targetUnits,
                                      boolean contextLimited, List<ReviewCaseLibrary.Example> references) {
        return discovery(revision, sourceRelationsEnabled, false, guidelinePrompt, primaryIds, raw, units, targetUnits,
                contextLimited, references);
    }

    static DiscoveryRequest discovery(String revision, boolean sourceRelationsEnabled, boolean recallFirst,
                                      String guidelinePrompt, List<String> primaryIds, List<ReviewInput.Segment> raw,
                                      List<ReviewUnit> units, List<ReviewUnit> targetUnits,
                                      boolean contextLimited, List<ReviewCaseLibrary.Example> references) {
        return discovery(revision, sourceRelationsEnabled, recallFirst, guidelinePrompt, primaryIds, raw, units, targetUnits,
                contextLimited, references, null);
    }

    static DiscoveryRequest discovery(String revision, boolean sourceRelationsEnabled, boolean recallFirst,
                                      String guidelinePrompt, List<String> primaryIds, List<ReviewInput.Segment> raw,
                                      List<ReviewUnit> units, List<ReviewUnit> targetUnits,
                                      boolean contextLimited, List<ReviewCaseLibrary.Example> references,
                                      ExperimentalBatchRendering rendering) {
        boolean hasReferences = references != null && !references.isEmpty();
        String baseline = recallFirst ? CandidateReviewPrompts.RECALL_DISCOVERY_PROMPT : CandidateReviewEngine.DISCOVERY_PROMPT;
        String system = (sourceRelationsEnabled ? SourceRelationPrompts.DISCOVERY : baseline)
                + guidelinePrompt + (hasReferences ? "\n" + CandidateReviewEngine.CASE_REFERENCE_CONTRACT : "");
        // Keep the original map construction and insertion sequence byte-for-byte compatible
        // with CandidateReviewEngine before extraction; only ownership moves to this factory.
        Map<String, Object> request = new LinkedHashMap<>(Map.of(
                "promptRevision", revision, "primaryIds", primaryIds,
                "raw", CandidateReviewEngine.compact(raw), "windows", CandidateReviewEngine.compactWindows(units),
                "targetWindows", CandidateReviewEngine.compactWindows(targetUnits),
                "contextLimited", contextLimited));
        if (rendering == null) request.put("readingPage", DialogueReadingPage.metadata());
        else {
            request.put("raw", rendering.render(raw));
            request.put("experimentalBatch", ExperimentalBatchRendering.VERSION);
            system = rendering.system(system);
        }
        if (hasReferences) request.put("referenceCases", references);
        return new DiscoveryRequest(system, JSON.writeValueAsString(request), hasReferences);
    }

    static String verificationSystem(CandidateReviewEngine.Candidate candidate, int evidenceLimit,
                                     String reference, CandidateReviewEngine.VerificationFeatures features) {
        return verificationSystem(candidate, evidenceLimit, reference, features, null);
    }

    static String verificationSystem(CandidateReviewEngine.Candidate candidate, int evidenceLimit,
                                     String reference, CandidateReviewEngine.VerificationFeatures features,
                                     ExperimentalBatchRendering rendering) {
        String system = candidate.proposal().sourceRelations() == null
                ? CandidateReviewEngine.verificationPrompt(evidenceLimit)
                : SourceRelationPrompts.verification(evidenceLimit);
        String built = CandidateReviewEngine.verificationSystem(system + reference, features);
        return rendering == null ? built : rendering.system(built);
    }

    static String verification(List<CandidateReviewEngine.Candidate> candidates, String failure,
                               String detail, boolean finalReviewBasisEnabled) {
        return verification(candidates, failure, detail, finalReviewBasisEnabled, null);
    }

    static String verification(List<CandidateReviewEngine.Candidate> candidates, String failure,
                               String detail, boolean finalReviewBasisEnabled, ExperimentalBatchRendering rendering) {
        Map<String, ReviewInput.Segment> raw = new LinkedHashMap<>();
        candidates.forEach(candidate -> candidate.raw().forEach(segment -> raw.put(segment.id(), segment)));
        Map<String, Object> request = new LinkedHashMap<>();
        boolean relations = candidates.stream().anyMatch(candidate -> candidate.proposal().sourceRelations() != null);
        request.put("promptRevision", relations ? CandidateReviewEngine.RELATION_REVISION : CandidateReviewEngine.REVISION);
        if (finalReviewBasisEnabled) request.put("verificationContract", FinalReviewBasisContract.VERSION);
        var orderedRaw = raw.values().stream()
                .sorted(Comparator.comparingLong(ReviewInput.Segment::startMs).thenComparing(ReviewInput.Segment::id)).toList();
        request.put("raw", rendering == null ? CandidateReviewEngine.compact(orderedRaw) : rendering.render(orderedRaw));
        if (rendering != null) request.put("experimentalBatch", ExperimentalBatchRendering.VERSION);
        if (!relations) request.put("candidates", candidates.stream().map(candidate -> Map.of(
                "candidateId", candidate.candidateId(), "anchorId", candidate.proposal().anchorId(),
                "axis", candidate.proposal().axis(), "hypothesisNotEvidence", candidate.proposal().reason(),
                "proposedEvidence", candidate.proposal().evidence(), "contextExpanded", candidate.contextExpanded(),
                "contextLimited", candidate.contextLimited(), "segmentIds", candidate.raw().stream().map(ReviewInput.Segment::id).toList())).toList());
        else request.put("candidates", candidates.stream().map(candidate -> Map.of(
                "candidateId", candidate.candidateId(), "anchorId", candidate.proposal().anchorId(),
                "axis", candidate.proposal().axis(), "sourceRelations", candidate.proposal().sourceRelations(),
                "proposedEvidence", candidate.proposal().evidence(), "contextExpanded", candidate.contextExpanded(),
                "contextLimited", candidate.contextLimited(), "segmentIds", candidate.raw().stream().map(ReviewInput.Segment::id).toList())).toList());
        if (failure != null) {
            Map<String, Object> repair = new LinkedHashMap<>(Map.of("attempt", 1, "failureCode", failure));
            if (detail != null) repair.put("failureDetail", detail);
            request.put("repair", repair);
        }
        return JSON.writeValueAsString(request);
    }
}
