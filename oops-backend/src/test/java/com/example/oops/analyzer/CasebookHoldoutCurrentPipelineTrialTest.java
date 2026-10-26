package com.example.oops.analyzer;

import java.util.List;
import java.io.InputStream;
import com.example.oops.domain.AnalyzerStatus;
import org.junit.jupiter.api.Test;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

class CasebookHoldoutCurrentPipelineTrialTest {
    @Test void reportsNoCandidateAsUnassessedRatherThanAffirmativePass() {
        var noCandidate = CasebookHoldoutCurrentPipelineTrial.classifyOutcome(AnalyzerStatus.SUCCESS,
                0, 0, false, 0, 0, 0, List.of());
        assertThat(noCandidate).isEqualTo(CasebookHoldoutCurrentPipelineTrial.PipelineOutcome.NO_CANDIDATE);
        assertThat(CasebookHoldoutCurrentPipelineTrial.compareExpected("REVIEW_REQUIRED", noCandidate))
                .isEqualTo("MISSED_EXPECTED_REVIEW");
        assertThat(CasebookHoldoutCurrentPipelineTrial.compareExpected("PASS", noCandidate))
                .isEqualTo("NO_CANDIDATE_NO_WARNING_NOT_VERIFIED");
    }

    @Test void distinguishesVerifiedPassFromCandidateDiscovery() {
        var verifiedPass = CasebookHoldoutCurrentPipelineTrial.classifyOutcome(AnalyzerStatus.SUCCESS,
                0, 0, false, 1, 0, 0, List.of("PASS"));
        assertThat(verifiedPass).isEqualTo(CasebookHoldoutCurrentPipelineTrial.PipelineOutcome.PASS_AFTER_VERIFICATION);
        assertThat(CasebookHoldoutCurrentPipelineTrial.compareExpected("PASS", verifiedPass)).isEqualTo("MATCH");

        var review = CasebookHoldoutCurrentPipelineTrial.classifyOutcome(AnalyzerStatus.SUCCESS,
                1, 0, false, 1, 0, 0, List.of("REVIEW_REQUIRED"));
        assertThat(review).isEqualTo(CasebookHoldoutCurrentPipelineTrial.PipelineOutcome.REVIEW_REQUIRED);
        assertThat(CasebookHoldoutCurrentPipelineTrial.compareExpected("PASS", review)).isEqualTo("FALSE_REVIEW");
        assertThat(CasebookHoldoutCurrentPipelineTrial.compareExpected("REVIEW_REQUIRED", review)).isEqualTo("MATCH");
        var partialReview = CasebookHoldoutCurrentPipelineTrial.classifyOutcome(AnalyzerStatus.PARTIAL,
                1, 1, false, 1, 0, 0, List.of("REVIEW_REQUIRED"));
        assertThat(partialReview).isEqualTo(CasebookHoldoutCurrentPipelineTrial.PipelineOutcome.REVIEW_REQUIRED_PARTIAL);
        assertThat(CasebookHoldoutCurrentPipelineTrial.compareExpected("REVIEW_REQUIRED", partialReview))
                .isEqualTo("PARTIAL_MATCH");
        assertThat(CasebookHoldoutCurrentPipelineTrial.classifyOutcome(AnalyzerStatus.PARTIAL,
                0, 1, false, 1, 0, 0, List.of("PASS")))
                .isEqualTo(CasebookHoldoutCurrentPipelineTrial.PipelineOutcome.INCOMPLETE);
    }

    @Test void makesTruncationUncertaintyAndFailuresExplicit() {
        assertThat(CasebookHoldoutCurrentPipelineTrial.classifyOutcome(AnalyzerStatus.SUCCESS,
                0, 0, false, 1, 1, 0, List.of("UNCERTAIN")))
                .isEqualTo(CasebookHoldoutCurrentPipelineTrial.PipelineOutcome.UNCERTAIN);
        assertThat(CasebookHoldoutCurrentPipelineTrial.classifyOutcome(AnalyzerStatus.PARTIAL,
                0, 1, false, 1, 0, 0, List.of("PASS")))
                .isEqualTo(CasebookHoldoutCurrentPipelineTrial.PipelineOutcome.INCOMPLETE);
        assertThat(CasebookHoldoutCurrentPipelineTrial.classifyOutcome(AnalyzerStatus.FAILED,
                0, 0, false, 0, 0, 0, List.of()))
                .isEqualTo(CasebookHoldoutCurrentPipelineTrial.PipelineOutcome.PIPELINE_FAILED);
    }

    @Test void auditUsesTheActualPartialAnalyzerStatus() throws Exception {
        var partial = JSON.readTree("""
                {"caseId":"partial","repeat":1,"status":"PARTIAL","findings":[{}],"unassessedSegmentIds":["s2"],
                 "diagnostics":{"truncated":false,"candidatePipeline":{"proposed":1,"uncertain":0,
                 "verificationFailed":0,"verificationCalls":1,"candidates":[{"state":"REVIEW_REQUIRED"}]}}}
                """);
        assertThat(CasebookHoldoutCurrentPipelineTrial.auditRow(partial))
                .containsEntry("pipelineOutcome", "REVIEW_REQUIRED_PARTIAL");
    }

    @Test void recordsFixedExecutionConfigurationWithoutPrivateInputs() {
        var config = CasebookHoldoutCurrentPipelineTrial.executionConfiguration();
        assertThat(config).containsEntry("model", "gpt-6-luna")
                .containsEntry("candidateDiscoveryEnabled", true)
                .containsEntry("reviewGuidelinesEnabled", true)
                .containsEntry("mechanismRoutingEnabled", false)
                .containsEntry("visualReviewerEnabled", false)
                .containsEntry("maxHttpAttemptsTotal", 24)
                .containsEntry("spacingBetweenAnalysesMs", 15000)
                .containsEntry("mediaEgress", false);
        assertThat(JSON.valueToTree(config).toString()).doesNotContain("expectedDecision", "sourceSegmentIds");
    }

    @Test void freezesAReferenceOnlyIsolationDesignWithoutApprovingExecution() throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("evals/casebook-reference-isolation-plan.json")) {
            assertThat(input).isNotNull();
            var plan = JSON.readTree(input);
            assertThat(plan.path("status").asText()).isEqualTo("DESIGN_ONLY_FRESH_APPROVAL_REQUIRED_FOR_MODEL_CALLS");
            assertThat(plan.path("target").has("expectedDecision")).isFalse();
            assertThat(plan.at("/conditions/0/id").asText()).isEqualTo("CURRENT_SELECTED_REFERENCE");
            assertThat(plan.at("/conditions/1/id").asText()).isEqualTo("GLOBAL_GUIDELINES_WITHOUT_EXAMPLES");
            assertThat(plan.path("fixedFactors").toString()).contains("shared system prompt hash", "Global review guidelines");
            assertThat(plan.at("/protocol/freshApprovalRequired").asBoolean()).isTrue();
            assertThat(plan.at("/promotionGate/noSharedPromptEditInThisExperiment").asBoolean()).isTrue();
            var frozen = plan.path("frozenArtifacts");
            assertThat(frozen.path("baselinePlanSha256").asText()).matches("[0-9a-f]{64}");
            assertThat(frozen.path("baselineManifestSha256").asText()).matches("[0-9a-f]{64}");
            assertThat(frozen.path("baselineResultsSha256").asText()).matches("[0-9a-f]{64}");
            assertThat(frozen.path("reviewPacketSha256").asText()).isEqualTo(
                    "95744e0a6e187e6cbdd96c4ffd412d2995f0307c7cd9108b8b1e186cc4205ad0");
            assertThat(frozen.path("storedTranscriptSha256").asText()).isEqualTo(
                    "6a2c6c53003ff491cc23e2f3d540ad768157046bdfaeaa6369bbdf4a1461d89a");
            assertThat(frozen.path("runtimeGuidelinesSha256").asText()).isEqualTo(
                    plan.at("/target/priorRuntimeSha256").asText());
            assertThat(frozen.path("manifestImplementationSha256").asText()).matches("[0-9a-f]{64}");
            assertThat(frozen.path("candidateReviewEngineSha256").asText()).matches("[0-9a-f]{64}");
            assertThat(frozen.path("reviewGuidelineLibrarySha256").asText()).matches("[0-9a-f]{64}");
            assertThat(frozen.path("speechReviewAnalyzerSha256").asText()).matches("[0-9a-f]{64}");
            assertThat(frozen.path("trialRunnerSha256").asText()).matches("[0-9a-f]{64}");
            assertThat(plan.at("/conditions/0/guidelineExamplesMaxCases").asInt()).isEqualTo(3);
            assertThat(plan.at("/conditions/1/guidelineExamplesMaxCases").asInt()).isZero();
            assertThat(plan.at("/conditions/1/description").asText()).contains("payload hash");
        }
    }

    @Test void retrospectivelySeparatesMissFromVerifiedPassWithoutProviderCalls() throws Exception {
        var missed = JSON.readTree("""
                {"caseId":"D05","repeat":1,"status":"SUCCESS","findings":[],"unassessedSegmentIds":[],
                 "diagnostics":{"truncated":false,"candidatePipeline":{"proposed":0,"uncertain":0,
                 "verificationFailed":0,"verificationCalls":0,"candidates":[]}}}
                """);
        var verifiedPass = JSON.readTree("""
                {"caseId":"D02","repeat":2,"status":"SUCCESS","findings":[],"unassessedSegmentIds":[],
                 "diagnostics":{"truncated":false,"candidatePipeline":{"proposed":1,"uncertain":0,
                 "verificationFailed":0,"verificationCalls":1,"candidates":[{"state":"PASS"}]}}}
                """);
        assertThat(CasebookHoldoutCurrentPipelineTrial.auditRow(missed))
                .containsEntry("pipelineOutcome", "NO_CANDIDATE")
                .containsEntry("expectedComparisonLocalOnly", "NO_APPROVED_EXPECTATION");
        assertThat(CasebookHoldoutCurrentPipelineTrial.auditRow(verifiedPass))
                .containsEntry("pipelineOutcome", "PASS_AFTER_VERIFICATION");
    }

    @Test void aggregatesNoCandidateAndVerifiedPassAsDifferentRecordedOutcomes() throws Exception {
        var recorded = JSON.readTree("""
                {"state":"COMPLETE","mediaSent":false,"planSha256":"plan","manifestSha256":"manifest",
                 "httpAttempts":7,"usageEstimatedUsd":0.003,"actualBillingConfirmed":false,
                 "executionConfiguration":{"model":"frozen-model","guidelineExampleMaxCases":3},"results":[
                   {"caseId":"D02","repeat":1,"status":"SUCCESS","expectedDecisionLocalOnly":"PASS",
                    "findings":[],"unassessedSegmentIds":[],"diagnostics":{"candidatePipeline":{"proposed":0,"verificationCalls":0,"candidates":[]}}},
                   {"caseId":"D02","repeat":2,"status":"SUCCESS","expectedDecisionLocalOnly":"PASS",
                    "findings":[],"unassessedSegmentIds":[],"diagnostics":{"candidatePipeline":{"proposed":1,"verificationCalls":1,"candidates":[{"state":"PASS"}]}}},
                   {"caseId":"D05","repeat":1,"status":"SUCCESS","expectedDecisionLocalOnly":"REVIEW_REQUIRED",
                    "findings":[],"unassessedSegmentIds":[],"diagnostics":{"candidatePipeline":{"proposed":0,"verificationCalls":0,"candidates":[]}}},
                   {"caseId":"D05","repeat":2,"status":"SUCCESS","expectedDecisionLocalOnly":"REVIEW_REQUIRED",
                    "findings":[],"unassessedSegmentIds":[],"diagnostics":{"candidatePipeline":{"proposed":0,"verificationCalls":0,"candidates":[]}}},
                   {"caseId":"D04","repeat":1,"status":"SUCCESS","expectedDecisionLocalOnly":"PASS",
                    "findings":[],"unassessedSegmentIds":[],"diagnostics":{"candidatePipeline":{"proposed":0,"verificationCalls":0,"candidates":[]}}},
                   {"caseId":"D04","repeat":2,"status":"SUCCESS","expectedDecisionLocalOnly":"PASS",
                    "findings":[],"unassessedSegmentIds":[],"diagnostics":{"candidatePipeline":{"proposed":0,"verificationCalls":0,"candidates":[]}}}
                 ]}
                """);
        var manifest = JSON.readTree("{\"implementationSha256\":\"implementation\"}");
        var audit = CasebookHoldoutCurrentPipelineTrial.auditRecordedResults(recorded, manifest);
        assertThat(audit).containsEntry("state", "AUDITED_OFFLINE_NO_PROVIDER_CALLS");
        var audited = JSON.valueToTree(audit);
        assertThat(audited.at("/executionConfiguration/model").asText()).isEqualTo("frozen-model");
        assertThat(audited.at("/outcomeCountsByCase/D02/NO_CANDIDATE").asInt()).isEqualTo(1);
        assertThat(audited.at("/outcomeCountsByCase/D02/PASS_AFTER_VERIFICATION").asInt()).isEqualTo(1);
        assertThat(audited.at("/outcomeCountsByCase/D05/NO_CANDIDATE").asInt()).isEqualTo(2);
        assertThat(audited.path("rows").toString()).contains("MISSED_EXPECTED_REVIEW", "NO_CANDIDATE_NO_WARNING_NOT_VERIFIED");

        var legacyRecorded = recorded.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) legacyRecorded).remove("executionConfiguration");
        var legacyAudit = JSON.valueToTree(
                CasebookHoldoutCurrentPipelineTrial.auditRecordedResults(legacyRecorded, manifest));
        assertThat(legacyAudit.path("executionConfiguration").isNull()).isTrue();
        assertThat(legacyAudit.path("configurationEvidence").asText()).contains("NO_CURRENT_CONFIGURATION_SUBSTITUTED");
    }

    @org.junit.jupiter.api.Tag("dataset-backed")
    @Test void loadsOnlyTheThreeUserReviewedExcerptWindows() throws Exception {
        var plan = CasebookHoldoutCurrentPipelineTrial.prepare();
        assertThat(plan.cases()).extracting(CasebookHoldoutCurrentPipelineTrial.CaseInput::caseId)
                .containsExactly("D02-lunch-skipping-study", "D05-taekwondo-belt-generalization",
                        "D04-math-major-quoted-misconception");
        assertThat(plan.cases()).extracting(CasebookHoldoutCurrentPipelineTrial.CaseInput::expectedDecisionLocalOnly)
                .containsExactly("PASS", "REVIEW_REQUIRED", "PASS");
        assertThat(plan.cases()).allSatisfy(c -> assertThat(c.review().segments()).isNotEmpty());
        assertThat(plan.cases().stream().flatMap(c -> c.sourceSegmentIds().stream()).distinct()).hasSize(45);
    }

    @org.junit.jupiter.api.Tag("dataset-backed")
    @Test void offlinePreflightPinsCurrentRuntimeRetrievalAndMakesNoProviderCalls() throws Exception {
        var manifest = CasebookHoldoutCurrentPipelineTrial.preflight(CasebookHoldoutCurrentPipelineTrial.prepare());
        assertThat(manifest.path("providerCalls").asInt()).isZero();
        assertThat(manifest.path("analyses").asInt()).isEqualTo(6);
        assertThat(manifest.path("cases").size()).isEqualTo(3);
        assertThat(manifest.path("mediaEgress").asBoolean()).isFalse();
        assertThat(manifest.path("cases").toString()).doesNotContain("2년 내내 점심을 안 먹고");
        assertThat(manifest.path("executionConfiguration").path("model").asText()).isEqualTo("gpt-6-luna");
    }

    @Test void worstCaseReservationStaysWithinFreshHalfDollarApproval() {
        assertThat(CasebookHoldoutCurrentPipelineTrial.reservationEstimate())
                .isCloseTo(.3864576, within(1e-12));
        assertThat(CasebookHoldoutCurrentPipelineTrial.reservationEstimate())
                .isLessThan(CasebookHoldoutCurrentPipelineTrial.CAP_USD);
        assertThat(CasebookHoldoutCurrentPipelineTrial.MAX_HTTP_TOTAL).isEqualTo(24);
        assertThat(CasebookHoldoutCurrentPipelineTrial.MAX_HTTP_PER_ANALYSIS).isEqualTo(4);
    }

    @Test void noExpectedDecisionCanEnterAnOpenAiUserPayload() {
        var userPayload = JSON.readTree("{\"raw\":[{\"id\":\"stt-index-1\",\"text\":\"a sample transcript\"}]}");
        assertThat(SocialDefectCurrentModelTrial.Guard.hasForbiddenLabel(userPayload)).isFalse();
        assertThat(SocialDefectCurrentModelTrial.Guard.hasForbiddenLabel(
                JSON.readTree("{\"expectedDecision\":\"PASS\",\"raw\":[]}"))).isTrue();
    }
}
