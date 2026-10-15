package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static com.example.oops.analyzer.ReviewEvaluation.*;

/** Speech-only candidate exploration then independent, candidate-scoped verification. */
final class CandidateReviewEngine {
    static final String REVISION = "2026-10-14-target-contract-diagnostics-37";
    static final String RELATION_REVISION = "2026-10-15-source-relations-contract-39";
    static final String POLICY = CandidateReviewPrompts.POLICY;
    static final String DISCOVERY_PROMPT = CandidateReviewPrompts.DISCOVERY_PROMPT;
    static final String VERIFICATION_PROMPT = CandidateReviewPrompts.VERIFICATION_PROMPT;
    static String verificationPrompt(int limit) {
        if (limit < 0) throw new IllegalArgumentException("Evidence limit must be nonnegative");
        return VERIFICATION_PROMPT.replace("evidence는 최대 8개로 허용 원문의 segmentId·role을 반환한다.",
                limit == 0 ? "evidence는 필요한 허용 원문의 segmentId·role을 반환한다. 고정 개수 상한은 없다. 동일 segmentId·role은 반복하지 않는다."
                        : "evidence는 최대 " + limit + "개로 허용 원문의 segmentId·role을 반환한다.");
    }
    private static final int MAX_TRACES = 200;
    private static final int MAX_CONTRACT_REPAIRS = 2;
    private static final int MAX_DISCOVERY_REPAIRS = 1;
    static final Set<String> REPAIRABLE_PROPOSALS = Set.of("PROPOSAL_EVIDENCE_NULL", "PROPOSAL_EVIDENCE_EMPTY",
            "PROPOSAL_EVIDENCE_NULL_ITEM", "PROPOSAL_EVIDENCE_OVER_BUDGET", "PROPOSAL_WINDOW_MISMATCH",
            "PROPOSAL_ANCHOR_EVIDENCE_REQUIRED");
    static final String DISCOVERY_REPAIR_PROMPT = CandidateReviewPrompts.DISCOVERY_REPAIR_PROMPT;
    static final Set<String> REPAIRABLE_FAILURES = Set.of("DECISION_UNKNOWN_EVIDENCE_ID",
            "TARGET_REQUIRED", "TARGET_EVIDENCE_REQUIRED", "TARGET_CONTEXT_EVIDENCE_REQUIRED", "TARGET_MENTION_RELATION",
            "EVIDENCE_BUDGET_EXCEEDED",
            "LINKED_PASS_CONTRAST_REQUIRED", "LINKED_PASS_CONTEXT_REQUIRED");
    static final String CONTRAST_REPAIR_PROMPT = CandidateReviewPrompts.CONTRAST_REPAIR_PROMPT;
    static String repairPrompt(String failure) {
        if ("EVIDENCE_BUDGET_EXCEEDED".equals(failure)) return EVIDENCE_BUDGET_REPAIR_PROMPT;
        if ("DECISION_UNKNOWN_EVIDENCE_ID".equals(failure)) return ID_REPAIR_PROMPT;
        if ("LINKED_PASS_CONTRAST_REQUIRED".equals(failure) || "LINKED_PASS_CONTEXT_REQUIRED".equals(failure))
            return CONTRAST_REPAIR_PROMPT;
        return TARGET_REPAIR_PROMPT;
    }
    static final String TARGET_REPAIR_PROMPT = CandidateReviewPrompts.TARGET_REPAIR_PROMPT;
    static final String EVIDENCE_BUDGET_REPAIR_PROMPT = CandidateReviewPrompts.EVIDENCE_BUDGET_REPAIR_PROMPT;
    static final String ID_REPAIR_PROMPT = CandidateReviewPrompts.ID_REPAIR_PROMPT;
    static final String CASE_REFERENCE_CONTRACT = CandidateReviewPrompts.CASE_REFERENCE_CONTRACT;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private CandidateReviewEngine() {}

    public record Quote(String segmentId, String quote) {}
    public record Proposal(String anchorId, String axis, String reason, List<Quote> evidence,
                           @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                           SourceRelations sourceRelations) {
        public Proposal { evidence = evidence == null ? null : Collections.unmodifiableList(new ArrayList<>(evidence)); }
        public Proposal(String anchorId, String axis, String reason, List<Quote> evidence) {
            this(anchorId,axis,reason,evidence,null);
        }
    }
    public record UncertainSegment(String segmentId, List<String> missingInformation) {}
    public record Discovery(List<String> reviewedSegmentIds, List<Proposal> candidates,
                            List<UncertainSegment> uncertainSegments, Boolean truncated, List<Proposal> visualCandidates) {
        public Discovery(List<String> ids, List<Proposal> candidates, List<UncertainSegment> uncertain, Boolean truncated) {
            this(ids, candidates, uncertain, truncated, List.of());
        }
    }
    public record Verification(String candidateId, TextReviewEngine.LlmDecision assessment) {}
    public record ProposalRepairResult(Proposal proposal) {}
    public record VerificationResult(List<Verification> verifications) {}
    record Candidate(String candidateId, Proposal proposal, List<ReviewInput.Segment> raw,
                     boolean contextExpanded, boolean contextLimited) {
        Candidate { raw = List.copyOf(raw); }
    }
    /** Frozen routing data, not evidence or an instruction to retain the discovery verdict. */
    private record FrozenReference(String discoveryRequestKey, String prompt) {}
    private record VerificationScope(String reference, String rawSha256) {}

    static TextReviewEngine.Result run(OpenAiClient client, AnalysisContext context, int maxCandidates) {
        return run(client, context, maxCandidates, null);
    }
    static TextReviewEngine.Result run(OpenAiClient client, AnalysisContext context, int maxCandidates, VisualContextReviewer visual) {
        return run(client, context, maxCandidates, visual, null);
    }
    static TextReviewEngine.Result run(OpenAiClient client, AnalysisContext context, int maxCandidates,
                                      VisualContextReviewer visual, ReviewCaseLibrary caseLibrary) {
        return run(client, context, maxCandidates, visual, caseLibrary, null);
    }
    static TextReviewEngine.Result run(OpenAiClient client, AnalysisContext context, int maxCandidates,
                                      VisualContextReviewer visual, ReviewCaseLibrary caseLibrary, ReviewGuidelineLibrary guidelines) {
        return run(client, context, maxCandidates, visual, caseLibrary, guidelines, null);
    }
    static TextReviewEngine.Result run(OpenAiClient client, AnalysisContext context, int maxCandidates,
                                      VisualContextReviewer visual, ReviewCaseLibrary caseLibrary, ReviewGuidelineLibrary guidelines,
                                      com.example.oops.service.ReviewRequestTraceStore.Session requestTrace) {
        // Historical replay/test contract. Spring explicitly passes its configured limit below.
        return run(client, context, maxCandidates, visual, caseLibrary, guidelines, requestTrace, 8);
    }
    static TextReviewEngine.Result run(OpenAiClient client, AnalysisContext context, int maxCandidates,
                                      VisualContextReviewer visual, ReviewCaseLibrary caseLibrary, ReviewGuidelineLibrary guidelines,
                                      com.example.oops.service.ReviewRequestTraceStore.Session requestTrace, int evidenceLimit) {
        return run(client,context,maxCandidates,visual,caseLibrary,guidelines,requestTrace,evidenceLimit,false);
    }
    static TextReviewEngine.Result run(OpenAiClient client, AnalysisContext context, int maxCandidates,
                                      VisualContextReviewer visual, ReviewCaseLibrary caseLibrary, ReviewGuidelineLibrary guidelines,
                                      com.example.oops.service.ReviewRequestTraceStore.Session requestTrace, int evidenceLimit,
                                      boolean sourceRelationsEnabled) {
        return run(client,context,maxCandidates,visual,caseLibrary,guidelines,requestTrace,evidenceLimit,
                sourceRelationsEnabled,null);
    }
    static TextReviewEngine.Result run(OpenAiClient client, AnalysisContext context, int maxCandidates,
                                      VisualContextReviewer visual, ReviewCaseLibrary caseLibrary, ReviewGuidelineLibrary guidelines,
                                      com.example.oops.service.ReviewRequestTraceStore.Session requestTrace, int evidenceLimit,
                                      boolean sourceRelationsEnabled, ContextMechanismRouter router) {
        if (evidenceLimit < 0) throw new IllegalArgumentException("Evidence limit must be nonnegative");
        if (maxCandidates < 1 || maxCandidates > 200) throw new IllegalArgumentException("Candidate budget must be 1..200");
        ReviewInput input = new ReviewInput(context.reviewInput().segments().stream()
                .filter(s -> s.type() == TimelineEventType.SPEECH).toList());
        var stats = new Stats();
        var routing = router == null ? null : router.session();
        stats.revision = sourceRelationsEnabled ? RELATION_REVISION : REVISION;
        stats.guidelineReference = guidelines == null ? null : guidelines.trace(TimelineEventType.SPEECH);
        String sourceFingerprint = caseLibrary == null ? null : ReviewCaseLibrary.fingerprint(input.segments());
        Set<String> explored = new LinkedHashSet<>(), unresolved = new LinkedHashSet<>();
        Set<String> fingerprints = new HashSet<>();
        List<Candidate> candidates = new ArrayList<>();
        List<Candidate> visualCandidates = new ArrayList<>();
        Map<String, FrozenReference> frozenReferences = new LinkedHashMap<>();
        List<ReviewEvaluation> evaluations = new ArrayList<>();
        Map<String, List<Observation>> observations = new LinkedHashMap<>();
        Map<String, Integer> pending = new HashMap<>();
        boolean partial = false;
        int counter = 0;
        for (var batch : TextReviewBatchPlanner.plan(input, TimelineEventType.SPEECH, 3)) {
            var primaryIds = batch.primary().stream().map(ReviewInput.Segment::id).toList();
            if (batch.oversizedPrimary()) { unresolved.addAll(primaryIds); partial = true; continue; }
            List<ReviewUnit> units = ReviewUnit.all(batch);
            List<ReviewUnit> targetUnits = CandidateContextWindow.discoveryUnits(batch);
            List<ReviewInput.Segment> raw = raw(batch);
            String discoveryRequestKey = "discovery-" + (stats.discoveryCalls + 1);
            var routed = routing == null ? null : routing.route(guidelines, raw, discoveryRequestKey);
            if (routed != null && stats.mechanismRouting.size() < MAX_TRACES) stats.mechanismRouting.add(routed.trace());
            String guidelinePrompt = stats.reference(guidelines, raw, discoveryRequestKey,
                    routed == null ? Set.of() : routed.keys());
            var references = caseLibrary == null ? null : caseLibrary.select(raw, sourceFingerprint);
            if (references != null && stats.retrievalTraces.size() < MAX_TRACES) stats.retrievalTraces.add(references.trace());
            stats.discoveryCalls++;
            Discovery response;
            try {
                Map<String, Object> request = new LinkedHashMap<>(Map.of(
                        "promptRevision", stats.revision, "primaryIds", primaryIds,
                        "raw", compact(raw), "windows", compactWindows(units), "targetWindows", compactWindows(targetUnits),
                        "contextLimited", batch.contextLimited()));
                boolean hasReferences = references != null && !references.examples().isEmpty();
                if (hasReferences) request.put("referenceCases", references.examples());
                response = client.completeAsJson((sourceRelationsEnabled ? SourceRelationPrompts.DISCOVERY : DISCOVERY_PROMPT)
                                + guidelinePrompt + (hasReferences ? "\n" + CASE_REFERENCE_CONTRACT : ""),
                        JSON.writeValueAsString(request), Discovery.class).orElse(null);
            } catch (RuntimeException ex) { response = null; }
            if (!validDiscovery(response, primaryIds)) {
                if (sourceRelationsEnabled) stats.trace(discoveryRequestKey,null,null,"INVALID_DISCOVERY",
                        response == null ? "DISCOVERY_NO_USABLE_RESPONSE" : "DISCOVERY_ENVELOPE_INVALID");
                stats.invalidProposals++; unresolved.addAll(primaryIds); partial = true; continue;
            }
            explored.addAll(response.reviewedSegmentIds());
            var reviewedIds = response.reviewedSegmentIds();
            primaryIds.stream().filter(id -> !reviewedIds.contains(id)).forEach(unresolved::add);
            partial |= response.truncated() || batch.contextLimited();
            if (batch.contextLimited()) stats.limitedBatches++;
            if (response.truncated()) stats.truncatedBatches++;
            if (response.truncated()) unresolved.addAll(primaryIds);
            List<Observation> discoveryUncertain = new ArrayList<>();
            for (var uncertain : response.uncertainSegments()) {
                unresolved.add(uncertain.segmentId()); stats.uncertain++;
                var s = input.find(uncertain.segmentId()).orElseThrow();
                var o = new Observation(s.id(), Decision.UNCERTAIN, "해석에 필수 정보 부족: " + String.join(" / ", uncertain.missingInformation()),
                        List.of(new EvidenceSpan(s.id(), s.text(), 0, s.text().codePointCount(0, s.text().length()), EvidenceRole.PRIMARY)),
                        uncertain.missingInformation(), new Details(null, null, null, null, null));
                discoveryUncertain.add(o);
                observations.computeIfAbsent(s.id(), key -> new ArrayList<>()).add(o);
            }
            List<String> supplied = raw.stream().map(ReviewInput.Segment::id).toList();
            evaluations.add(new ReviewEvaluation("speech-review:discovery", ExecutionStatus.SUCCESS,
                    response.reviewedSegmentIds(), discoveryUncertain, supplied));
            if (response.visualCandidates() == null) { unresolved.addAll(primaryIds); partial = true; }
            List<Proposal> allProposals = new ArrayList<>(response.candidates());
            if (response.visualCandidates() != null) allProposals.addAll(response.visualCandidates());
            for (int proposalIndex = 0; proposalIndex < allProposals.size(); proposalIndex++) {
                var proposal = allProposals.get(proposalIndex);
                boolean needsVisual = proposalIndex >= response.candidates().size();
                stats.proposed++;
                String id = "candidate-" + ++counter;
                if (proposal != null && (sourceRelationsEnabled && !needsVisual
                        ? proposal.sourceRelations() == null || proposal.reason() != null
                        : proposal.sourceRelations() != null)) {
                    stats.invalidProposals++; unresolved.addAll(primaryIds); partial = true;
                    String proposedAnchor = proposal.anchorId();
                    String safeAnchor = raw.stream().anyMatch(s -> s.id().equals(proposedAnchor)) ? proposedAnchor : null;
                    String safeAxis = Set.of("TARGET_TREATMENT","EXPRESSION_CONTENT").contains(proposal.axis() == null ? "" : proposal.axis())
                            ? proposal.axis() : null;
                    stats.trace(id,safeAnchor,safeAxis,"INVALID_PROPOSAL","PROPOSAL_RELATION_MODE");
                    continue;
                }
                // Approach A: server supplies evidence quotes from raw; the model only picks line IDs/roles.
                if (proposal != null) {
                    Map<String, ReviewInput.Segment> rawById = new HashMap<>();
                    raw.forEach(s -> rawById.put(s.id(), s));
                    proposal = normalizeProposal(proposal, rawById);
                }
                List<ReviewUnit> allowedUnits = proposal != null && "TARGET_TREATMENT".equals(proposal.axis()) ? targetUnits : units;
                Candidate candidate = candidate(id, proposal, batch, allowedUnits, response.reviewedSegmentIds(), input, !needsVisual);
                if (candidate == null) {
                    String failure = proposalFailure(proposal, batch, allowedUnits, response.reviewedSegmentIds());
                    // A relation proposal must not fall back to the legacy conclusion/reason repair schema.
                    // Keep malformed roles unassessed; a relation-specific discovery repair is not enabled yet.
                    if (REPAIRABLE_PROPOSALS.contains(failure == null ? "" : failure)
                            && proposal.sourceRelations() == null) {
                        if (stats.discoveryRepairCalls < MAX_DISCOVERY_REPAIRS
                                && stats.repairCalls + stats.discoveryRepairCalls < MAX_CONTRACT_REPAIRS) {
                            var repaired = repairProposal(client, id, proposal, batch, allowedUnits,
                                    response.reviewedSegmentIds(), input, guidelinePrompt, stats, failure, !needsVisual);
                            if (repaired != null) { candidate = repaired; proposal = repaired.proposal(); }
                        } else if (stats.repairs.size() < MAX_TRACES) stats.repairs.add(new CandidateReviewDiagnostics.RepairTrace(
                                id, failure, "NOT_ATTEMPTED_BUDGET", failure));
                    }
                }
                if (candidate == null) {
                    stats.invalidProposals++; unresolved.addAll(primaryIds); partial = true;
                    stats.invalidProposal(id, proposal, batch, allowedUnits, response.reviewedSegmentIds()); continue;
                }
                // Deduplicate exact anchored evidence only; different criticisms of a quote are preserved.
                String fingerprint = needsVisual + "|" + proposal.anchorId() + "|" + proposal.axis() + "|" + proposal.reason()
                        + "|" + proposal.evidence().stream().map(q -> q.segmentId() + "=" + q.quote()).sorted().toList()
                        + (proposal.sourceRelations() == null ? "" : "|" + JSON.writeValueAsString(proposal.sourceRelations()));
                if (!fingerprints.add(fingerprint)) { stats.duplicates++; continue; }
                if (candidates.size() + visualCandidates.size() >= maxCandidates
                        || needsVisual && visualCandidates.size() >= (visual == null ? 3 : visual.maxCandidates())) {
                    stats.budgetSkipped++; unresolved.add(proposal.anchorId()); partial = true;
                    stats.trace(id, proposal.anchorId(), proposal.axis(), "BUDGET_SKIPPED"); continue;
                }
                if (needsVisual) visualCandidates.add(candidate);
                else {
                    candidates.add(candidate);
                    // Approved historical cases are copied too, never retrieved afresh at verification.
                    String reference = guidelinePrompt;
                    String referenceKey = discoveryRequestKey;
                    // Explicit v6 archive only: choose unit references once using the validated candidate,
                    // then freeze exactly that payload for verification/repair. v1-v5 behavior stays unchanged.
                    if (guidelines != null && guidelines.contextUnitArchive()) {
                        referenceKey = "unit-" + id;
                        var evidenceIds = new HashSet<>(candidate.proposal().evidence().stream().map(Quote::segmentId).toList());
                        reference = stats.candidateReference(guidelines, candidate, referenceKey,
                                routed == null ? Set.of() : routed.keysFor(evidenceIds));
                    }
                    if (references != null && !references.examples().isEmpty()) reference += "\n" + CASE_REFERENCE_CONTRACT
                            + "\nreferenceCases: " + JSON.writeValueAsString(references.examples());
                    var frozen = new FrozenReference(referenceKey, reference);
                    frozenReferences.put(id, frozen);
                    stats.snapshot(candidate, frozen);
                }
                if (candidate.contextExpanded()) stats.expandedCandidates++;
                if (candidate.contextLimited()) {
                    stats.verificationLimitedCandidates++; unresolved.add(proposal.anchorId()); partial = true;
                }
                pending.merge(proposal.anchorId(), 1, Integer::sum);
            }
        }
        List<RiskFinding> findings = new ArrayList<>();
        // Share calls only with the same frozen references. Per-candidate segment permissions still apply.
        Map<VerificationScope, List<Candidate>> referenceGroups = new LinkedHashMap<>();
        for (var c : candidates) {
            String reference = frozenReferences.get(c.candidateId()).prompt();
            var scope = new VerificationScope(reference, stats.snapshots.get(c.candidateId()).rawSha256());
            referenceGroups.computeIfAbsent(scope, ignored -> new ArrayList<>()).add(c);
        }
        for (var entry : referenceGroups.entrySet()) for (int start = 0; start < entry.getValue().size(); start += 4) {
            List<Candidate> group = entry.getValue().subList(start, Math.min(start + 4, entry.getValue().size()));
            String guidelinePrompt = entry.getKey().reference();
            stats.verificationCalls++;
            VerificationResult response;
            String responseFailure = null;
            int failuresBefore = client.failureCount();
            String requestSystem = (group.get(0).proposal().sourceRelations() == null ? verificationPrompt(evidenceLimit)
                    : SourceRelationPrompts.verification(evidenceLimit)) + guidelinePrompt;
            String requestInput = verificationRequest(group, null);
            try {
                response = client.completeAsJson(requestSystem, requestInput,
                        VerificationResult.class).orElse(null);
            } catch (RuntimeException ex) { response = null; responseFailure = "REQUEST_EXCEPTION"; }
            if (requestTrace != null && requestTrace.enabled()) {
                try {
                    requestTrace.record(requestSystem, requestInput, client.replayRequestBody(requestSystem, requestInput), response,
                            response == null ? "NO_PARSED_RESPONSE" : "PARSED_RESPONSE_NOT_YET_VALIDATED");
                } catch (RuntimeException ignored) {
                    requestTrace.record(requestSystem, requestInput, response, "PROVIDER_BODY_UNAVAILABLE");
                }
            }
            if (response == null && responseFailure == null) responseFailure = client.failureCount() > failuresBefore
                    ? client.failureCode().orElse("NO_PARSED_RESPONSE") : "NO_PARSED_RESPONSE";
            Set<String> expected = new HashSet<>(group.stream().map(Candidate::candidateId).toList());
            if (response != null && response.verifications() != null && (response.verifications().size() > group.size()
                    || response.verifications().stream().anyMatch(v -> v == null || !expected.contains(v.candidateId())))) {
                response = null; responseFailure = "UNEXPECTED_CANDIDATE_RESPONSE";
            }
            var returned = response;
            for (var c : group) {
                var matches = returned == null || returned.verifications() == null ? List.<Verification>of()
                        : returned.verifications().stream().filter(v -> v != null && c.candidateId().equals(v.candidateId())).toList();
                Validation validation = matches.size() == 1 ? validate(c, matches.get(0).assessment(), input, evidenceLimit)
                        : invalid(responseFailure != null ? responseFailure : matches.isEmpty() ? "MISSING_CANDIDATE" : "DUPLICATE_CANDIDATE");
                stats.evidenceCount(c, "INITIAL", matches.size() == 1 ? matches.get(0).assessment() : null,
                        evidenceLimit, validation.failureCode());
                if (REPAIRABLE_FAILURES.contains(validation.failureCode() == null ? "" : validation.failureCode())) {
                    if (stats.repairCalls + stats.discoveryRepairCalls < MAX_CONTRACT_REPAIRS) {
                        validation = repairContract(client, c, input, guidelinePrompt, stats, validation.failureCode(), evidenceLimit,
                                matches.size() == 1 ? matches.get(0).assessment() : null);
                    } else if (stats.repairs.size() < MAX_TRACES) {
                        stats.repairs.add(new CandidateReviewDiagnostics.RepairTrace(c.candidateId(),
                                validation.failureCode(), "NOT_ATTEMPTED_BUDGET", validation.failureCode()));
                    }
                }
                Observation observation = validation.observation();
                if (observation == null) {
                    stats.verificationFailed++; unresolved.add(c.proposal().anchorId()); partial = true;
                    stats.trace(c, "VERIFICATION_FAILED", validation.failureCode());
                    continue;
                }
                pending.computeIfPresent(observation.anchorId(), (key, value) -> value - 1);
                observations.computeIfAbsent(observation.anchorId(), key -> new ArrayList<>()).add(observation);
                evaluations.add(new ReviewEvaluation("speech-review:verification", ExecutionStatus.SUCCESS,
                        List.of(observation.anchorId()), List.of(observation), c.raw().stream().map(ReviewInput.Segment::id).toList()));
                stats.trace(c, observation.decision().name());
                if (observation.decision() == Decision.REVIEW_REQUIRED) {
                    findings.add(TextReviewEngine.finding(context, input.find(observation.anchorId()).orElseThrow(), observation));
                } else if (observation.decision() == Decision.PASS) stats.rejected++;
                else { stats.uncertain++; unresolved.add(observation.anchorId()); partial = true; }
            }
        }
        for (var c : visualCandidates) {
            VisualContextReviewer.Result result;
            String reference = stats.candidateReference(guidelines, c, "visual-" + c.candidateId());
            try {
                result = visual == null
                    ? new VisualContextReviewer.Result(invalid("VISUAL_NOT_CONFIGURED"),
                    new VisualContextReviewer.Trace(c.candidateId(), c.proposal().anchorId(), "NOT_ASSESSED", "VISUAL_NOT_CONFIGURED", List.of(), List.of(), null), false)
                    : visual.review(context, c, reference);
            } catch (RuntimeException ex) {
                result = new VisualContextReviewer.Result(invalid("VISUAL_STAGE_EXCEPTION"),
                        new VisualContextReviewer.Trace(c.candidateId(), c.proposal().anchorId(), "NOT_ASSESSED", "VISUAL_STAGE_EXCEPTION", List.of(), List.of(), null), false);
            }
            if (result.modelCalled()) stats.verificationCalls++;
            String initialFailure = result.validation().failureCode();
            if (visual != null && REPAIRABLE_FAILURES.contains(initialFailure == null ? "" : initialFailure)) {
                if (stats.repairCalls + stats.discoveryRepairCalls < MAX_CONTRACT_REPAIRS) {
                    stats.repairCalls++;
                    try { result = visual.review(context, c, reference, initialFailure); }
                    catch (RuntimeException ex) {
                        result = new VisualContextReviewer.Result(invalid("VISUAL_STAGE_EXCEPTION"),
                                new VisualContextReviewer.Trace(c.candidateId(), c.proposal().anchorId(), "NOT_ASSESSED",
                                        "VISUAL_STAGE_EXCEPTION", List.of(), List.of(), null), false);
                    }
                    if (result.modelCalled()) stats.verificationCalls++;
                    if (stats.repairs.size() < MAX_TRACES) stats.repairs.add(new CandidateReviewDiagnostics.RepairTrace(
                            c.candidateId(), initialFailure, result.validation().observation() == null ? "FAILED" : "RECOVERED",
                            result.validation().failureCode()));
                } else if (stats.repairs.size() < MAX_TRACES) stats.repairs.add(new CandidateReviewDiagnostics.RepairTrace(
                        c.candidateId(), initialFailure, "NOT_ATTEMPTED_BUDGET", initialFailure));
            }
            stats.visualTraces.add(result.trace());
            var o = result.validation().observation();
            if (o == null) {
                stats.verificationFailed++; unresolved.add(c.proposal().anchorId()); partial = true;
                stats.trace(c, "VERIFICATION_FAILED", result.validation().failureCode());
                continue;
            }
            pending.computeIfPresent(o.anchorId(), (key, value) -> value - 1);
            observations.computeIfAbsent(o.anchorId(), key -> new ArrayList<>()).add(o);
            evaluations.add(new ReviewEvaluation("speech-review:visual-verification", ExecutionStatus.SUCCESS,
                    List.of(o.anchorId()), List.of(o), c.raw().stream().map(ReviewInput.Segment::id).toList()));
            stats.trace(c, o.decision().name());
            if (o.decision() == Decision.REVIEW_REQUIRED) {
                var finding = TextReviewEngine.finding(context, input.find(o.anchorId()).orElseThrow(), o);
                var visualTrace = result.trace();
                finding.appendReason("선택적 장면 검증(전체 영상·음성 아님): " + visualTrace.observations().stream()
                        .map(v -> visualTrace.frames().stream().filter(f -> f.frameId().equals(v.frameId())).findFirst().orElseThrow().timestampMs()
                                / 1000.0 + "초 " + v.description()).collect(java.util.stream.Collectors.joining(" / "))
                        + " 연결 해석: " + visualTrace.connectionReason());
                findings.add(finding);
            } else if (o.decision() == Decision.PASS) stats.rejected++;
            else { stats.uncertain++; unresolved.add(o.anchorId()); partial = true; }
        }
        input.segments().stream().map(ReviewInput.Segment::id).filter(id -> !explored.contains(id)).forEach(unresolved::add);
        pending.forEach((id, count) -> { if (count > 0) unresolved.add(id); });
        partial |= !unresolved.isEmpty();
        AnalyzerStatus status = explored.isEmpty() ? AnalyzerStatus.FAILED : partial ? AnalyzerStatus.PARTIAL : AnalyzerStatus.SUCCESS;
        String notice = partial || status == AnalyzerStatus.FAILED ?
                "후보 탐색 %d/%d구간 · 미확인 %d구간 · 탐색 응답 검증 실패 %d건 · 검증 누락/실패 %d건 · 판단 보류 %d건 · 예산 제외 %d건 · 문맥 제한 %d배치 · 후보 잘림 %d배치 · 검증 문맥 제한 %d후보"
                        .formatted(explored.size(), input.segments().size(), unresolved.size(), stats.invalidProposals,
                                stats.verificationFailed, stats.uncertain, stats.budgetSkipped, stats.limitedBatches, stats.truncatedBatches,
                                stats.verificationLimitedCandidates) : null;
        List<ReviewDiagnostics.SegmentTrace> traces = input.segments().stream().limit(400).map(s -> {
            List<Observation> items = observations.getOrDefault(s.id(), List.of());
            var state = items.stream().anyMatch(o -> o.decision() == Decision.REVIEW_REQUIRED) ? ReviewDiagnostics.State.REVIEW_REQUIRED
                    : items.stream().anyMatch(o -> o.decision() == Decision.UNCERTAIN) ? ReviewDiagnostics.State.UNCERTAIN
                    : unresolved.contains(s.id()) ? ReviewDiagnostics.State.NOT_RETURNED
                    : items.isEmpty() ? ReviewDiagnostics.State.NO_CANDIDATE : ReviewDiagnostics.State.PASS;
            var decisions = items.stream().limit(4).map(o -> new ReviewDiagnostics.DecisionTrace(o.decision(),
                    clip(o.reason(), 240), o.details().category(), o.details().target(),
                    o.missingInformation().stream().map(m -> clip(m, 120)).toList(),
                    o.evidence().stream().map(e -> new ReviewDiagnostics.EvidenceLink(e.segmentId(), e.role())).toList(),
                    o.reason().length() > 240 || o.missingInformation().stream().anyMatch(m -> m.length() > 120))).toList();
            return new ReviewDiagnostics.SegmentTrace(s.id(), s.type(), s.startMs(), s.endMs(), state,
                    decisions, List.of(), 0, items.size() > 4);
        }).toList();
        var diagnostic = new ReviewDiagnostics("speech-review", status, input.segments().size(),
                input.segments().size() > 400, Map.of(), traces).withCandidatePipeline(stats.finish(explored.size()));
        return new TextReviewEngine.Result(findings, evaluations, status, notice, unresolved.stream().sorted().toList(), List.of(), diagnostic);
    }

    private static String verificationRequest(List<Candidate> candidates, String failure) {
        return verificationRequest(candidates, failure, null);
    }
    private static String verificationRequest(List<Candidate> candidates, String failure, String detail) {
        Map<String, ReviewInput.Segment> raw = new LinkedHashMap<>();
        candidates.forEach(c -> c.raw().forEach(s -> raw.put(s.id(), s)));
        Map<String, Object> request = new LinkedHashMap<>();
        boolean relations = candidates.stream().anyMatch(c -> c.proposal().sourceRelations() != null);
        request.put("promptRevision", relations ? RELATION_REVISION : REVISION);
        request.put("raw", compact(raw.values().stream()
                .sorted(Comparator.comparingLong(ReviewInput.Segment::startMs).thenComparing(ReviewInput.Segment::id)).toList()));
        if (!relations) request.put("candidates", candidates.stream().map(c -> Map.of("candidateId", c.candidateId(),
                "anchorId", c.proposal().anchorId(), "axis", c.proposal().axis(),
                "hypothesisNotEvidence", c.proposal().reason(),
                "proposedEvidence", c.proposal().evidence(),
                "contextExpanded", c.contextExpanded(), "contextLimited", c.contextLimited(),
                "segmentIds", c.raw().stream().map(ReviewInput.Segment::id).toList())).toList());
        else request.put("candidates", candidates.stream().map(c -> Map.of("candidateId", c.candidateId(),
                "anchorId", c.proposal().anchorId(), "axis", c.proposal().axis(),
                "sourceRelations", c.proposal().sourceRelations(), "proposedEvidence", c.proposal().evidence(),
                "contextExpanded", c.contextExpanded(), "contextLimited", c.contextLimited(),
                "segmentIds", c.raw().stream().map(ReviewInput.Segment::id).toList())).toList());
        if (failure != null) {
            Map<String, Object> repair = new LinkedHashMap<>(Map.of("attempt", 1, "failureCode", failure));
            if (detail != null) repair.put("failureDetail", detail);
            request.put("repair", repair);
        }
        return JSON.writeValueAsString(request);
    }
    private static Candidate repairProposal(OpenAiClient client, String id, Proposal original,
            TextReviewBatchPlanner.Batch batch, List<ReviewUnit> units, List<String> reviewed,
            ReviewInput input, String reference, Stats stats, String initial, boolean freezeWindow) {
        stats.discoveryRepairCalls++; stats.discoveryCalls++;
        Candidate result = null;
        String failure = "PROPOSAL_REPAIR_NO_RESPONSE";
        try {
            var windows = units.stream().filter(u -> u.anchorId().equals(original.anchorId())).toList();
            Set<String> permitted = new HashSet<>(); windows.forEach(w -> permitted.addAll(w.segmentIds()));
            String request = JSON.writeValueAsString(Map.of("promptRevision", REVISION,
                    "anchorId", original.anchorId(), "axis", original.axis(),
                    "hypothesisNotEvidence", original.reason(), "raw", compact(raw(batch).stream().filter(s -> permitted.contains(s.id())).toList()),
                    "allowedWindows", compactWindows(windows), "repair", Map.of("attempt", 1, "failureCode", initial)));
            var response = client.completeAsJson(DISCOVERY_REPAIR_PROMPT + reference, request, ProposalRepairResult.class).orElse(null);
            if (response != null && response.proposal() == null) failure = "PROPOSAL_REPAIR_NO_CANDIDATE";
            else if (response != null) {
                var p = response.proposal();
                // Approach A: fill repaired proposal quotes from raw as well.
                Map<String, ReviewInput.Segment> rawById = new HashMap<>();
                raw(batch).forEach(s -> rawById.put(s.id(), s));
                p = normalizeProposal(p, rawById);
                if (p.sourceRelations() != null || !original.anchorId().equals(p.anchorId()) || !original.axis().equals(p.axis()))
                    failure = "PROPOSAL_REPAIR_SCOPE_CHANGED";
                else {
                    failure = proposalFailure(p, batch, units, reviewed);
                    if (failure == null) result = candidate(id, p, batch, units, reviewed, input, freezeWindow);
                }
            }
        } catch (RuntimeException ex) { failure = "PROPOSAL_REPAIR_REQUEST_EXCEPTION"; }
        if (stats.repairs.size() < MAX_TRACES) stats.repairs.add(new CandidateReviewDiagnostics.RepairTrace(id, initial,
                result == null ? "FAILED" : "RECOVERED", failure));
        return result;
    }
    private static Validation repairContract(OpenAiClient client, Candidate c, ReviewInput input,
                                             String reference, Stats stats, String initial, int evidenceLimit,
                                             TextReviewEngine.LlmDecision previous) {
        stats.repairCalls++; stats.verificationCalls++;
        Validation result;
        TextReviewEngine.LlmDecision repaired = null;
        int failuresBefore = client.failureCount();
        try {
            var correction = repairPrompt(initial);
            var response = client.completeAsJson((c.proposal().sourceRelations() == null ? verificationPrompt(evidenceLimit)
                            : SourceRelationPrompts.verification(evidenceLimit)) + reference + "\n" + correction,
                    verificationRequest(List.of(c), initial, targetDetail(c, previous, initial)), VerificationResult.class).orElse(null);
            if (response == null) result = invalid(client.failureCount() > failuresBefore
                    ? client.failureCode().orElse("NO_PARSED_RESPONSE") : "NO_PARSED_RESPONSE");
            else if (response.verifications() == null || response.verifications().size() != 1
                    || response.verifications().get(0) == null
                    || !c.candidateId().equals(response.verifications().get(0).candidateId()))
                result = invalid("REPAIR_RESPONSE_SHAPE");
            else {
                repaired = response.verifications().get(0).assessment();
                result = validate(c, repaired, input, evidenceLimit);
            }
            stats.evidenceCount(c, "REPAIR", response != null && response.verifications() != null
                    && response.verifications().size() == 1 && response.verifications().get(0) != null
                    ? response.verifications().get(0).assessment() : null, evidenceLimit, result.failureCode());
        } catch (RuntimeException ex) { result = invalid("REQUEST_EXCEPTION"); }
        if (stats.repairs.size() < MAX_TRACES) stats.repairs.add(new CandidateReviewDiagnostics.RepairTrace(
                c.candidateId(), initial, result.observation() == null ? "FAILED" : "RECOVERED", result.failureCode(),
                previous == null ? null : previous.decision(), repaired == null ? null : repaired.decision(),
                previous != null && repaired != null ? !Objects.equals(previous.decision(), repaired.decision()) : null));
        return result;
    }
    private static boolean validDiscovery(Discovery r, List<String> primary) {
        if (r == null || r.reviewedSegmentIds() == null || r.candidates() == null || r.uncertainSegments() == null
                || r.truncated() == null || r.candidates().size() + (r.visualCandidates() == null ? 0 : r.visualCandidates().size()) > 12
                || r.reviewedSegmentIds().isEmpty()) return false;
        Set<String> reviewed = new HashSet<>(r.reviewedSegmentIds());
        if (reviewed.size() != r.reviewedSegmentIds().size() || !primary.containsAll(reviewed)) return false;
        Set<String> uncertain = new HashSet<>();
        return r.uncertainSegments().size() <= primary.size() && r.uncertainSegments().stream().allMatch(u ->
                u != null && reviewed.contains(u.segmentId()) && uncertain.add(u.segmentId()) && information(u.missingInformation(), true));
    }
    private static Candidate candidate(String id, Proposal p, TextReviewBatchPlanner.Batch batch,
                                       List<ReviewUnit> units, List<String> reviewed, ReviewInput input, boolean freezeWindow) {
        if (proposalFailure(p, batch, units, reviewed) != null) return null;
        var selected = units.stream().filter(u -> u.anchorId().equals(p.anchorId()) &&
                u.segmentIds().containsAll(p.evidence().stream().map(Quote::segmentId).toList()))
                // Dense earlier speech must not win merely by having more segments.
                .max(Comparator.comparingInt((ReviewUnit u) -> !"TARGET_TREATMENT".equals(p.axis()) ? 0 : switch (u.view()) {
                    case CENTRED -> 3;
                    case LEADING -> 2;
                    case TRAILING -> 1;
                }).thenComparingInt(u -> u.segmentIds().size())).orElse(null);
        if (selected == null) return null;
        var base = raw(batch).stream().filter(s -> selected.segmentIds().contains(s.id())).toList();
        // Text verification reads exactly one window already advertised to discovery: no trimming,
        // no unseen transcript extension, and no replacement of an earlier target cue by later speech.
        if (freezeWindow) return new Candidate(id, p, base, false, selected.limited() || batch.contextLimited());
        if (!"TARGET_TREATMENT".equals(p.axis())) return new Candidate(id, p, base, false, false);
        // Keep all proposed quotes plus intervening raw, not an incidental early edge of a broad discovery view.
        // Otherwise dense earlier speech can exhaust the 60s budget before the later continuation is read.
        Set<String> quoted = new HashSet<>(); p.evidence().forEach(q -> quoted.add(q.segmentId()));
        int first = 0, last = base.size() - 1;
        while (!quoted.contains(base.get(first).id())) first++;
        while (!quoted.contains(base.get(last).id())) last--;
        var window = CandidateContextWindow.extend(input, p.anchorId(), base.subList(first, last + 1));
        return new Candidate(id, p, window.raw(), window.expanded(), window.limited());
    }
    private static String proposalFailure(Proposal p, TextReviewBatchPlanner.Batch batch,
                                          List<ReviewUnit> units, List<String> reviewed) {
        if (p == null) return "PROPOSAL_NULL";
        if (p.anchorId() == null || !reviewed.contains(p.anchorId())) return "PROPOSAL_ANCHOR_NOT_REVIEWED";
        if (!Set.of("TARGET_TREATMENT", "EXPRESSION_CONTENT").contains(p.axis() == null ? "" : p.axis())) return "PROPOSAL_AXIS";
        if (p.sourceRelations() == null && !useful(p.reason(), 300)) return "PROPOSAL_REASON";
        if (p.sourceRelations() != null && p.reason() != null) return "PROPOSAL_RELATION_MODE";
        // Previously all four shape failures collapsed into PROPOSAL_EVIDENCE_SHAPE, so a stored
        // INVALID_PROPOSAL could not be told apart (missing quotes vs over-budget) after the fact.
        if (p.evidence() == null) return "PROPOSAL_EVIDENCE_NULL";
        if (p.evidence().isEmpty()) return "PROPOSAL_EVIDENCE_EMPTY";
        Map<String, ReviewInput.Segment> supplied = new HashMap<>(); raw(batch).forEach(s -> supplied.put(s.id(), s));
        boolean missingQuote = false;
        for (var q : p.evidence()) {
            if (q == null) { missingQuote = true; continue; }
            if (!supplied.containsKey(q.segmentId())) return "PROPOSAL_UNKNOWN_EVIDENCE_ID";
            if (q.quote() == null || q.quote().isBlank() || !supplied.get(q.segmentId()).text().contains(q.quote()))
                return "PROPOSAL_QUOTE_NOT_IN_RAW";
        }
        if (missingQuote) return "PROPOSAL_EVIDENCE_NULL_ITEM";
        if (p.evidence().size() > 8) return "PROPOSAL_EVIDENCE_OVER_BUDGET";
        if (new HashSet<>(p.evidence()).size() != p.evidence().size()) return "PROPOSAL_DUPLICATE_EVIDENCE";
        if (p.sourceRelations() != null) {
            String failure = p.sourceRelations().failure(p.anchorId(),p.evidence().stream().map(Quote::segmentId).toList());
            if (failure != null) return failure;
        }
        if (p.evidence().stream().noneMatch(q -> q.segmentId().equals(p.anchorId()))) return "PROPOSAL_ANCHOR_EVIDENCE_REQUIRED";
        if (units.stream().noneMatch(u -> u.anchorId().equals(p.anchorId())
                && u.segmentIds().containsAll(p.evidence().stream().map(Quote::segmentId).toList()))) return "PROPOSAL_WINDOW_MISMATCH";
        return null;
    }
    static CandidateReviewDiagnostics.WindowMatchTrace inspectWindows(Proposal p, List<ReviewUnit> units) {
        var windows = units.stream().filter(u -> Objects.equals(u.anchorId(),p.anchorId())).toList();
        Set<String> evidence = new HashSet<>();
        if (p.evidence() != null) p.evidence().stream().filter(Objects::nonNull).map(Quote::segmentId).forEach(evidence::add);
        Set<String> union = new HashSet<>(); windows.forEach(w -> union.addAll(w.segmentIds()));
        int outside = (int)evidence.stream().filter(id -> !union.contains(id)).count();
        int matching = (int)windows.stream().filter(w -> w.segmentIds().containsAll(evidence)).count();
        int missing = windows.stream().mapToInt(w -> (int)evidence.stream().filter(id -> !w.segmentIds().contains(id)).count())
                .min().orElse(evidence.size());
        String detail = windows.isEmpty() ? "NO_ANCHOR_WINDOW" : outside > 0 ? "EVIDENCE_OUTSIDE_ALL_WINDOWS"
                : matching == 0 ? "EVIDENCE_SPANS_DIFFERENT_WINDOWS" : "MATCHED";
        return new CandidateReviewDiagnostics.WindowMatchTrace(detail,windows.size(),matching,outside,missing);
    }
    record Validation(Observation observation, String failureCode) {}
    private static Validation invalid(String code) { return new Validation(null, code); }

    // Approach A (candidate path only): the model selects line IDs and roles; the server supplies the
    // exact raw quote from that line, so the model never copies or rewrites transcript text. targetMention
    // (a short referent the model picks) is kept and still checked against the server-filled TARGET line.
    private static Proposal normalizeProposal(Proposal p, Map<String, ReviewInput.Segment> raw) {
        if (p == null || p.evidence() == null) return p;
        // A role ID is already a model-selected source, not an inferred relationship. Materialize
        // its evidence deterministically, but never remove existing entries or evade the 8-ID/window
        // checks below. Malformed/unknown role IDs still fail closed; legacy proposals are unchanged.
        List<Quote> selected = new ArrayList<>(p.evidence());
        if (p.sourceRelations() != null && p.sourceRelations().failure(p.anchorId(),raw.keySet()) == null) {
            Set<String> present = new HashSet<>();
            selected.stream().filter(Objects::nonNull).forEach(q -> present.add(q.segmentId()));
            for (String id : p.sourceRelations().selectedIds())
                if (present.add(id)) selected.add(new Quote(id,null));
        }
        var filled = selected.stream().map(q -> {
            if (q == null) return q;
            var seg = raw.get(q.segmentId());
            return seg == null ? q : new Quote(q.segmentId(), seg.text());
        }).toList();
        return new Proposal(p.anchorId(), p.axis(), p.reason(), filled, p.sourceRelations());
    }
    private static TextReviewEngine.LlmDecision normalizeAssessment(TextReviewEngine.LlmDecision d,
                                                                    Map<String, ReviewInput.Segment> raw) {
        if (d == null) return d;
        var anchor = raw.get(d.segmentId());
        String evidenceText = anchor != null ? anchor.text() : d.evidenceText();
        List<TextReviewEngine.LlmEvidence> filled = d.evidence() == null ? null : d.evidence().stream().map(e -> {
            if (e == null) return e;
            var seg = raw.get(e.segmentId());
            return seg == null ? e : new TextReviewEngine.LlmEvidence(e.segmentId(), seg.text(), e.role());
        }).toList();
        return new TextReviewEngine.LlmDecision(d.segmentId(), d.decision(), evidenceText, d.reason(),
                d.category(), d.target(), d.score(), d.context(), d.reading(), d.missingInformation(), filled,
                d.targetType(), d.targetRelation(), d.targetReason(), d.alternativeInterpretation(), d.targetMention());
    }

    static Validation validate(Candidate c, TextReviewEngine.LlmDecision assessment, ReviewInput input) {
        return validate(c, assessment, input, 8);
    }
    static Validation validate(Candidate c, TextReviewEngine.LlmDecision assessment, ReviewInput input, int evidenceLimit) {
        // Server fills quotes from raw before any shape/linkage check (Approach A).
        Map<String, ReviewInput.Segment> raw = new HashMap<>();
        c.raw().forEach(s -> raw.put(s.id(), s));
        TextReviewEngine.LlmDecision d = normalizeAssessment(assessment, raw);
        return validateFilled(c, d, input, evidenceLimit);
    }
    private static Validation validateFilled(Candidate c, TextReviewEngine.LlmDecision d, ReviewInput input, int evidenceLimit) {
        if (d == null || !c.proposal().anchorId().equals(d.segmentId()) || !useful(d.reason(), 400)
                || d.evidence() == null || d.evidence().isEmpty() || evidenceLimit > 0 && d.evidence().size() > Math.max(12, evidenceLimit)
                || d.evidenceText() == null || d.evidenceText().isBlank() || d.context() != null || d.reading() != null) return invalid("ASSESSMENT_SHAPE");
        if (evidenceLimit > 0 && d.evidence().size() > evidenceLimit) {
            // Only small overflows with actual permitted quotes may request one bounded rewrite.
            // Never truncate an assessment or silently exceed the configured legacy limit.
            if (d.evidence().stream().anyMatch(e -> e == null || e.segmentId() == null || e.quote() == null
                    || e.quote().isBlank() || !Set.of("PRIMARY", "TARGET", "CONTEXT").contains(e.role() == null ? "" : e.role())
                    || c.raw().stream().noneMatch(s -> s.id().equals(e.segmentId()) && s.text().contains(e.quote())))
                    || d.evidence().stream().noneMatch(e -> "PRIMARY".equals(e.role())
                    && c.proposal().anchorId().equals(e.segmentId()) && d.evidenceText().equals(e.quote())))
                return invalid("ASSESSMENT_SHAPE");
            return invalid("EVIDENCE_BUDGET_EXCEEDED");
        }
        try {
            Decision decision = Decision.valueOf(d.decision());
            if (!information(d.missingInformation(), decision == Decision.UNCERTAIN)) return invalid("MISSING_INFORMATION_CONTRACT");
            if (decision != Decision.REVIEW_REQUIRED && (d.category() != null || d.target() != null || d.score() != null
                    || d.targetType() != null || d.targetRelation() != null || d.targetReason() != null || d.targetMention() != null)) return invalid("NON_REVIEW_FIELDS");
            if (d.score() != null && (!Double.isFinite(d.score()) || d.score() < 0 || d.score() > 1)) return invalid("INVALID_SCORE");
            if (decision == Decision.REVIEW_REQUIRED && !useful(d.alternativeInterpretation(), 300)) return invalid("ALTERNATIVE_INTERPRETATION_REQUIRED");
            if (decision == Decision.PASS && "TARGET_TREATMENT".equals(c.proposal().axis())
                    && c.proposal().evidence().stream().map(Quote::segmentId).distinct().count() > 1) {
                if (!useful(d.alternativeInterpretation(), 300)) return invalid("LINKED_PASS_CONTRAST_REQUIRED");
                Set<String> proposedIds = new HashSet<>();
                c.proposal().evidence().forEach(q -> proposedIds.add(q.segmentId()));
                if (d.evidence().stream().noneMatch(e -> e != null && "CONTEXT".equals(e.role())
                        && !c.proposal().anchorId().equals(e.segmentId()) && proposedIds.contains(e.segmentId())))
                    return invalid("LINKED_PASS_CONTEXT_REQUIRED");
            }
            if (d.alternativeInterpretation() != null && d.alternativeInterpretation().length() > 300) return invalid("ALTERNATIVE_INTERPRETATION_LENGTH");
            if (d.target() != null) {
                String detail = inspectTarget(d).failureDetail();
                if (detail != null) return invalid(switch (detail) {
                    case "TARGET_RELATION_MISMATCH" -> "TARGET_MENTION_RELATION";
                    case "TARGET_SEPARATE_CONTEXT_MISSING" -> "TARGET_CONTEXT_EVIDENCE_REQUIRED";
                    default -> "TARGET_EVIDENCE_REQUIRED";
                });
            }
            if (decision == Decision.REVIEW_REQUIRED && "TARGET_TREATMENT".equals(c.proposal().axis())
                    && !"GRAPHIC_METAPHOR".equals(d.category())
                    && (d.target() == null || d.target().isBlank())) return invalid("TARGET_REQUIRED");
            if (decision == Decision.REVIEW_REQUIRED && Set.of("BELITTLEMENT", "MOCKERY", "STRONG_NEGATIVE_REVIEW",
                    "GENERALIZATION", "DISCRIMINATION", "HATE_SPEECH").contains(d.category() == null ? "" : d.category())
                    && (d.target() == null || d.target().isBlank())) return invalid("TARGET_REQUIRED");
            if (d.target() == null && (d.targetType() != null || d.targetRelation() != null || d.targetReason() != null || d.targetMention() != null)) return invalid("ORPHAN_TARGET_FIELDS");
            var anchor = input.find(d.segmentId()).orElseThrow();
            var batch = new TextReviewBatchPlanner.Batch(List.of(anchor), c.raw().stream().filter(s -> !s.id().equals(anchor.id())).toList(), false, false);
            var unit = new ReviewUnit(anchor.id(), c.raw().stream().map(ReviewInput.Segment::id).toList(),
                    c.raw().get(0).startMs(), c.raw().get(c.raw().size() - 1).endMs(), false, ReviewUnit.View.CENTRED);
            Observation o = TextReviewEngine.observation(d, anchor, batch, List.of(unit),
                    evidenceLimit == 0 ? 3 * c.raw().size() : evidenceLimit);
            var evaluation = new ReviewEvaluation("speech-review:verification", ExecutionStatus.SUCCESS,
                    List.of(anchor.id()), List.of(o), c.raw().stream().map(ReviewInput.Segment::id).toList());
            if (!ReviewEvidenceValidator.validate(input, evaluation).isEmpty()) return invalid("RAW_EVIDENCE_VALIDATION");
            if (decision == Decision.REVIEW_REQUIRED) {
                var failure = TextReviewEngine.publicationFailure(o, SpeechReviewAnalyzer.ALLOWED_CATEGORIES);
                if (failure != null) return invalid("PUBLICATION_" + failure.name());
            }
            return new Validation(o, null);
        } catch (TextReviewEngine.RejectedDecision ex) { return invalid("DECISION_" + ex.failure.name()); }
        catch (IllegalArgumentException | NoSuchElementException | NullPointerException ex) { return invalid("INVALID_DECISION_VALUE"); }
    }
    private static boolean information(List<String> items, boolean required) {
        return items != null && (required ? !items.isEmpty() && items.size() <= 6 && items.stream().allMatch(s -> useful(s, 200)) : items.isEmpty());
    }
    private static TextReviewEngine.LlmDecision filled(Candidate c, TextReviewEngine.LlmDecision d) {
        Map<String, ReviewInput.Segment> raw = new HashMap<>(); c.raw().forEach(s -> raw.put(s.id(), s));
        return normalizeAssessment(d, raw);
    }
    private static String targetDetail(Candidate c, TextReviewEngine.LlmDecision d, String failure) {
        if ("TARGET_REQUIRED".equals(failure)) return "TARGET_NAME_REQUIRED";
        if (!Set.of("TARGET_EVIDENCE_REQUIRED", "TARGET_MENTION_RELATION", "TARGET_CONTEXT_EVIDENCE_REQUIRED")
                .contains(failure == null ? "" : failure) || d == null) return null;
        return inspectTarget(filled(c, d)).failureDetail();
    }
    /** Same predicate and precedence as the historic validator; no automatic role/target correction. */
    static CandidateReviewDiagnostics.TargetContractTrace inspectTarget(TextReviewEngine.LlmDecision d) {
        String mention = d.targetMention() == null ? d.target() : d.targetMention();
        var evidence = d.evidence() == null ? List.<TextReviewEngine.LlmEvidence>of() : d.evidence();
        var targets = evidence.stream().filter(e -> e != null && "TARGET".equals(e.role())).toList();
        var matches = targets.stream().filter(e -> e.quote() != null && mention != null && e.quote().contains(mention)).toList();
        Set<String> mentionIds = new HashSet<>(); matches.forEach(e -> mentionIds.add(e.segmentId()));
        int separate = (int) evidence.stream().filter(e -> e != null && "CONTEXT".equals(e.role())
                && !mentionIds.contains(e.segmentId()) && e.quote() != null && !e.quote().isBlank()).count();
        boolean nameValid = useful(d.target(), 200), mentionValid = useful(mention, 200), reasonValid = useful(d.targetReason(), 300);
        String detail = null;
        if (d.target() != null) {
            if (!nameValid) detail = "TARGET_NAME_INVALID";
            else if (!mentionValid) detail = "TARGET_MENTION_INVALID";
            else if (!reasonValid) detail = "TARGET_REASON_INVALID";
            else if (targets.isEmpty()) detail = "TARGET_ROLE_MISSING";
            else if (matches.isEmpty()) detail = "TARGET_MENTION_NOT_IN_TARGET_RAW";
            else if (!mention.equals(d.target()) && !"CONTEXTUAL".equals(d.targetRelation())) detail = "TARGET_RELATION_MISMATCH";
            else if (!mention.equals(d.target()) && separate == 0) detail = "TARGET_SEPARATE_CONTEXT_MISSING";
        }
        return new CandidateReviewDiagnostics.TargetContractTrace(detail, d.targetMention() != null,
                nameValid, mentionValid, reasonValid, targets.size(), matches.size(), separate);
    }
    private static boolean useful(String text, int max) { return text != null && !text.isBlank() && text.length() <= max; }
    private static String clip(String text, int max) {
        if (text.length() <= max) return text;
        int end = max - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "…";
    }
    private static List<ReviewInput.Segment> raw(TextReviewBatchPlanner.Batch batch) {
        var all = new ArrayList<>(batch.primary()); all.addAll(batch.context());
        return all.stream().sorted(Comparator.comparingLong(ReviewInput.Segment::startMs).thenComparing(ReviewInput.Segment::id)).toList();
    }
    private static List<Map<String, Object>> compact(List<ReviewInput.Segment> raw) {
        return raw.stream().map(s -> Map.<String, Object>of("id", s.id(), "startMs", s.startMs(), "endMs", s.endMs(), "text", s.text())).toList();
    }
    /** Identical raw windows share one ID list; anchor permissions remain explicit. */
    private static List<Map<String, Object>> compactWindows(List<ReviewUnit> units) {
        Map<List<String>, Set<String>> grouped = new LinkedHashMap<>();
        units.forEach(u -> grouped.computeIfAbsent(u.segmentIds(), key -> new LinkedHashSet<>()).add(u.anchorId()));
        return grouped.entrySet().stream().map(e -> Map.<String, Object>of("anchorIds", List.copyOf(e.getValue()),
                "segmentIds", e.getKey())).toList();
    }
    private static final class Stats {
        String revision = REVISION;
        int discoveryCalls, verificationCalls, proposed, duplicates, invalidProposals, rejected, uncertain, verificationFailed, budgetSkipped, limitedBatches, truncatedBatches;
        int expandedCandidates, verificationLimitedCandidates, repairCalls, discoveryRepairCalls;
        int traceCount;
        final List<CandidateReviewDiagnostics.Trace> traces = new ArrayList<>();
        final List<VisualContextReviewer.Trace> visualTraces = new ArrayList<>();
        final List<ReviewCaseLibrary.Trace> retrievalTraces = new ArrayList<>();
        final List<ReviewGuidelineLibrary.SelectionTrace> contextSelections = new ArrayList<>();
        final List<ContextMechanismRouter.Trace> mechanismRouting = new ArrayList<>();
        final List<CandidateReviewDiagnostics.RepairTrace> repairs = new ArrayList<>();
        final List<CandidateReviewDiagnostics.EvidenceCountTrace> evidenceCounts = new ArrayList<>();
        final Map<String, CandidateReviewDiagnostics.InputSnapshot> snapshots = new LinkedHashMap<>();
        void snapshot(Candidate c, FrozenReference reference) {
            snapshots.put(c.candidateId(), new CandidateReviewDiagnostics.InputSnapshot(reference.discoveryRequestKey(),
                    ReviewGuidelineLibrary.retrievalHash(JSON.writeValueAsString(compact(c.raw()))),
                    ReviewGuidelineLibrary.retrievalHash(reference.prompt()),
                    ReviewGuidelineLibrary.retrievalHash(JSON.writeValueAsString(c.proposal()))));
        }
        void evidenceCount(Candidate c, String attempt, TextReviewEngine.LlmDecision d, int limit, String failure) {
            if (evidenceCounts.size() >= MAX_TRACES) return;
            Set<String> segments = new HashSet<>();
            Set<List<String>> pairs = new HashSet<>();
            int duplicates = 0, invalid = 0;
            if (d != null && d.evidence() != null) for (var e : d.evidence()) {
                if (e == null || e.segmentId() == null || e.role() == null) { invalid++; continue; }
                segments.add(e.segmentId());
                if (!pairs.add(List.of(e.segmentId(), e.role()))) duplicates++;
                if (!Set.of("PRIMARY", "TARGET", "CONTEXT").contains(e.role())
                        || c.raw().stream().noneMatch(s -> s.id().equals(e.segmentId()))) invalid++;
            }
            evidenceCounts.add(new CandidateReviewDiagnostics.EvidenceCountTrace(c.candidateId(), attempt,
                    d == null || d.evidence() == null ? null : d.evidence().size(), segments.size(), pairs.size(),
                    duplicates, invalid, limit, failure,
                    d == null ? null : inspectTarget(filled(c, d))));
        }
        ReviewGuidelineLibrary.Trace guidelineReference;
        void invalidProposal(String id, Proposal p, TextReviewBatchPlanner.Batch batch,
                             List<ReviewUnit> units, List<String> reviewed) {
            var anchor = p == null ? null : raw(batch).stream().filter(s -> s.id().equals(p.anchorId())).findFirst().orElse(null);
            String axis = p != null && Set.of("TARGET_TREATMENT", "EXPRESSION_CONTENT").contains(p.axis() == null ? "" : p.axis()) ? p.axis() : null;
            traceCount++;
            // Raw-free shape metadata: counts and a boolean only, never the quote text, so a stored
            // INVALID_PROPOSAL can later be told apart (over-budget vs window-mismatch vs missing anchor).
            Integer evidenceCount = p == null || p.evidence() == null ? null : p.evidence().size();
            Integer outOfWindow = null;
            Boolean anchorQuotePresent = null;
            if (p != null && p.evidence() != null && p.anchorId() != null) {
                Set<String> allowed = new HashSet<>();
                units.stream().filter(u -> u.anchorId().equals(p.anchorId())).forEach(u -> allowed.addAll(u.segmentIds()));
                outOfWindow = (int) p.evidence().stream().filter(q -> q != null
                        && !allowed.contains(q.segmentId())).map(Quote::segmentId).distinct().count();
                anchorQuotePresent = p.evidence().stream().anyMatch(q -> q != null && p.anchorId().equals(q.segmentId()));
            }
            String failure = proposalFailure(p,batch,units,reviewed);
            if (traces.size() < MAX_TRACES) traces.add(new CandidateReviewDiagnostics.Trace(id,
                    anchor == null ? null : anchor.id(), axis, "INVALID_PROPOSAL",
                    anchor == null ? null : anchor.startMs(), anchor == null ? null : anchor.endMs(),
                    List.of(), false, false, failure,
                    evidenceCount, outOfWindow, anchorQuotePresent,null,
                    "PROPOSAL_WINDOW_MISMATCH".equals(failure) ? inspectWindows(p,units) : null));
        }
        String reference(ReviewGuidelineLibrary library, Collection<ReviewInput.Segment> raw, String requestKey, Set<String> keys) {
            if (library == null) return "";
            var selection = keys.isEmpty() ? library.select(TimelineEventType.SPEECH, raw, requestKey)
                    : library.select(TimelineEventType.SPEECH, raw, requestKey,List.of(),keys);
            if (contextSelections.size() < MAX_TRACES) contextSelections.add(selection.trace());
            return selection.prompt();
        }
        String candidateReference(ReviewGuidelineLibrary library, Candidate c, String requestKey) {
            return candidateReference(library,c,requestKey,Set.of());
        }
        String candidateReference(ReviewGuidelineLibrary library, Candidate c, String requestKey, Set<String> keys) {
            if (library == null) return "";
            // Already-validated proposal quotes are routing text, not the model's reason or an inferred correction.
            var focus = c.proposal().evidence().stream().distinct().map(q -> {
                var s = c.raw().stream().filter(r -> r.id().equals(q.segmentId())).findFirst().orElseThrow();
                return new ReviewInput.Segment(s.id(), s.type(), s.startMs(), s.endMs(), q.quote(), s.confidence());
            }).sorted(Comparator.comparingLong(ReviewInput.Segment::startMs)
                    .thenComparingLong(ReviewInput.Segment::endMs).thenComparing(ReviewInput.Segment::id)
                    .thenComparing(ReviewInput.Segment::text)).toList();
            var selection = keys.isEmpty() ? library.select(TimelineEventType.SPEECH, c.raw(), requestKey, focus)
                    : library.select(TimelineEventType.SPEECH, c.raw(), requestKey, focus, keys);
            if (contextSelections.size() < MAX_TRACES) contextSelections.add(selection.trace());
            return selection.prompt();
        }
        void trace(String id, String anchor, String axis, String state, String failure) {
            traceCount++;
            if (traces.size() < MAX_TRACES) traces.add(new CandidateReviewDiagnostics.Trace(id,anchor,axis,state,
                    null,null,List.of(),false,false,failure));
        }
        void trace(String id, String anchor, String axis, String state) {
            traceCount++; if (traces.size() < MAX_TRACES) traces.add(new CandidateReviewDiagnostics.Trace(id, anchor, axis, state));
        }
        void trace(Candidate candidate, String state) {
            trace(candidate, state, null);
        }
        void trace(Candidate candidate, String state, String failureCode) {
            traceCount++;
            if (traces.size() < MAX_TRACES) traces.add(new CandidateReviewDiagnostics.Trace(candidate.candidateId(),
                    candidate.proposal().anchorId(), candidate.proposal().axis(), state,
                    candidate.raw().stream().mapToLong(ReviewInput.Segment::startMs).min().orElseThrow(),
                    candidate.raw().stream().mapToLong(ReviewInput.Segment::endMs).max().orElseThrow(),
                    candidate.raw().stream().map(ReviewInput.Segment::id).toList(), candidate.contextExpanded(), candidate.contextLimited(), failureCode,
                    null, null, null, snapshots.get(candidate.candidateId())));
        }
        CandidateReviewDiagnostics finish(int explored) {
            return new CandidateReviewDiagnostics(revision, discoveryCalls, verificationCalls, explored, proposed, duplicates,
                    invalidProposals, rejected, uncertain, verificationFailed, budgetSkipped, limitedBatches, truncatedBatches,
                    expandedCandidates, verificationLimitedCandidates, traceCount > MAX_TRACES, traces, visualTraces, retrievalTraces,
                    guidelineReference, contextSelections, repairCalls, repairs, discoveryRepairCalls, evidenceCounts, mechanismRouting);
        }
    }
}
