package com.example.oops.analyzer;

import com.example.oops.domain.AnalyzerStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CasebookReferenceIsolationModelTrialTest {
    @TempDir Path temp;

    @Test void reservedWorstCaseFitsApprovedCapWithoutAssumingCacheDiscounts() {
        assertThat(CasebookReferenceIsolationModelTrial.reservation(40, 120_000))
                .isCloseTo(.644096, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(CasebookReferenceIsolationModelTrial.reservation(40, 120_000))
                .isLessThan(CasebookReferenceIsolationModelTrial.CAP_USD);
    }

    @Test void incompleteOrContractDamagedAnalysesCannotContinueAsComparableRuns() {
        var validEmpty = result(AnalyzerStatus.SUCCESS, 0, 0, 0, 0, 0, 0, false, List.of(), List.of());
        assertThat(CasebookReferenceIsolationModelTrial.isComparable(validEmpty, "NO_CANDIDATE")).isTrue();
        assertThat(CasebookReferenceIsolationModelTrial.isComparable(validEmpty, "INCOMPLETE")).isFalse();

        assertThat(CasebookReferenceIsolationModelTrial.isComparable(
                result(AnalyzerStatus.PARTIAL, 0, 0, 0, 0, 0, 0, false, List.of(), List.of()), "NO_CANDIDATE"))
                .isFalse();
        assertThat(CasebookReferenceIsolationModelTrial.isComparable(
                result(AnalyzerStatus.SUCCESS, 1, 0, 0, 0, 0, 0, false, List.of(), List.of()), "INCOMPLETE"))
                .isFalse();
        assertThat(CasebookReferenceIsolationModelTrial.isComparable(
                result(AnalyzerStatus.SUCCESS, 0, 1, 0, 0, 0, 0, false, List.of(), List.of()), "INCOMPLETE"))
                .isFalse();
        assertThat(CasebookReferenceIsolationModelTrial.isComparable(
                result(AnalyzerStatus.SUCCESS, 0, 0, 1, 0, 0, 0, false, List.of(), List.of()), "INCOMPLETE"))
                .isFalse();
        assertThat(CasebookReferenceIsolationModelTrial.isComparable(
                result(AnalyzerStatus.SUCCESS, 0, 0, 0, 1, 0, 0, false, List.of(), List.of()), "INCOMPLETE"))
                .isFalse();
        assertThat(CasebookReferenceIsolationModelTrial.isComparable(
                result(AnalyzerStatus.SUCCESS, 0, 0, 0, 0, 0, 0, true, List.of(), List.of()), "INCOMPLETE"))
                .isFalse();
        assertThat(CasebookReferenceIsolationModelTrial.isComparable(
                result(AnalyzerStatus.SUCCESS, 0, 0, 0, 0, 0, 0, false, List.of("stt-index-1"), List.of()), "INCOMPLETE"))
                .isFalse();
        assertThat(CasebookReferenceIsolationModelTrial.isComparable(
                result(AnalyzerStatus.SUCCESS, 0, 0, 0, 0, 0, 0, false, List.of(), List.of("stt-index-1")), "INCOMPLETE"))
                .isFalse();
        assertThat(CasebookReferenceIsolationModelTrial.isComparable(
                result(AnalyzerStatus.SUCCESS, 0, 0, 0, 0, 1, 0, false, List.of(), List.of()), "INCOMPLETE"))
                .isFalse();
        assertThat(CasebookReferenceIsolationModelTrial.isComparable(
                result(AnalyzerStatus.SUCCESS, 0, 0, 0, 0, 0, 1, false, List.of(), List.of()), "INCOMPLETE"))
                .isFalse();
        var truncatedOverall = new TextReviewEngine.Result(List.of(), List.of(), AnalyzerStatus.SUCCESS, null,
                List.of(), List.of(), new ReviewDiagnostics("test", AnalyzerStatus.SUCCESS, 1, true,
                Map.of(), List.of(), null, new CandidateReviewDiagnostics("test", 1, 0, 1, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, false, List.of(), List.of(), List.of(), null,
                List.of(), 0, List.of(), 0, List.of())));
        assertThat(CasebookReferenceIsolationModelTrial.isComparable(truncatedOverall, "NO_CANDIDATE")).isFalse();
    }

    @Test void outboundGuardAddsEightThousandTokenCeilingAndRejectsLabelsAndMedia() throws Exception {
        var guard = new CasebookReferenceIsolationModelTrial.Guard(temp);
        byte[] allowed = requestBody(Map.of("segments", List.of(Map.of("id", "s1", "text", "example"))));
        byte[] bounded = guard.accept(allowed, CasebookReferenceIsolationModelTrial.ENDPOINT);
        JsonNode request = JSON.readTree(bounded);
        assertThat(request.path("max_completion_tokens").asInt()).isEqualTo(8_000);
        assertThat(request.path("model").asText()).isEqualTo("gpt-6-luna");
        assertThat(JSON.readTree(java.nio.file.Files.readString(temp.resolve("reservation.json")))
                .path("httpAttempts").asInt()).isEqualTo(1);

        var labelGuard = new CasebookReferenceIsolationModelTrial.Guard(temp);
        byte[] labeled = requestBody(Map.of("expectedDecision", "REVIEW_REQUIRED", "segments", List.of()));
        assertThatThrownBy(() -> labelGuard.accept(labeled, CasebookReferenceIsolationModelTrial.ENDPOINT))
                .isInstanceOf(IllegalArgumentException.class);

        var mediaGuard = new CasebookReferenceIsolationModelTrial.Guard(temp);
        byte[] media = requestBody(Map.of("transcript", "text", "image_url", "data:image/jpeg;base64,abc"));
        assertThatThrownBy(() -> mediaGuard.accept(media, CasebookReferenceIsolationModelTrial.ENDPOINT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void frozenPlanAllowsOnlyTheApprovedD05FactorComparison() {
        var plan = new CasebookReferenceIsolationModelTrial.Plan(
                "casebook-reference-isolation-model-trial-1", "scope", "D05-taekwondo-belt-generalization",
                "REVIEW_REQUIRED", List.of("stt-index-1"), "a", "b", "c", "d", "e", Map.of(),
                "gpt-6-luna", "none", 5,
                List.of("CURRENT_SELECTED_REFERENCE", "GLOBAL_GUIDELINES_WITHOUT_EXAMPLES",
                        "GLOBAL_GUIDELINES_WITHOUT_EXAMPLES", "CURRENT_SELECTED_REFERENCE",
                        "GLOBAL_GUIDELINES_WITHOUT_EXAMPLES", "CURRENT_SELECTED_REFERENCE",
                        "CURRENT_SELECTED_REFERENCE", "GLOBAL_GUIDELINES_WITHOUT_EXAMPLES",
                        "CURRENT_SELECTED_REFERENCE", "GLOBAL_GUIDELINES_WITHOUT_EXAMPLES"),
                List.of(new CasebookReferenceIsolationModelTrial.Condition("CURRENT_SELECTED_REFERENCE", 3,
                                List.of("podcast-dev#podcast-boast-response")),
                        new CasebookReferenceIsolationModelTrial.Condition("GLOBAL_GUIDELINES_WITHOUT_EXAMPLES", 0, List.of())),
                Map.of(), 40, 4, 120_000, 8_000, 15_000, true, .70, .644096);
        CasebookReferenceIsolationModelTrial.validate(plan);
        var wrongModel = new CasebookReferenceIsolationModelTrial.Plan(
                plan.schemaVersion(), plan.scope(), plan.sourceCaseId(), plan.expectedDecisionLocalOnly(),
                plan.sourceSegmentIds(), plan.reviewSha256(), plan.transcriptSha256(), plan.runtimeSha256(),
                plan.offlinePreflightSha256(), plan.implementationSha256(), plan.implementation(), "other-model",
                plan.reasoningEffort(), plan.repeatsPerCondition(), plan.conditionOrder(), plan.conditions(),
                plan.expectedDiscoveryFingerprints(), plan.maxHttpAttemptsTotal(), plan.maxHttpAttemptsPerAnalysis(),
                plan.maxRequestBodyBytes(), plan.maxOutputTokens(), plan.spacingBetweenAnalysesMs(),
                plan.outboundRequestGuardDryRunPassed(), plan.hardCapUsd(),
                plan.worstCaseReservedEstimateUsd());
        assertThatThrownBy(() -> CasebookReferenceIsolationModelTrial.validate(wrongModel))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] requestBody(Object userInput) throws Exception {
        return JSON.writeValueAsBytes(Map.of(
                "model", "gpt-6-luna", "reasoning_effort", "none",
                "messages", List.of(Map.of("role", "system", "content", "system prompt"),
                        Map.of("role", "user", "content", JSON.writeValueAsString(userInput)))));
    }

    private static TextReviewEngine.Result result(AnalyzerStatus status, int invalidProposals,
            int verificationFailed, int budgetSkipped, int limitedBatches, int truncatedBatches,
            int repairCalls, boolean truncated, List<String> unassessed, List<String> conflicts) {
        var candidatePipeline = new CandidateReviewDiagnostics("test", 1, 0, 1, 0, 0,
                invalidProposals, 0, 0, verificationFailed, budgetSkipped, limitedBatches,
                truncatedBatches, 0, 0, truncated, List.of(), List.of(), List.of(), null,
                List.of(), repairCalls, List.of(), 0, List.of());
        var diagnostics = new ReviewDiagnostics("test", status, 1, false, Map.of(), List.of(), null, candidatePipeline);
        return new TextReviewEngine.Result(List.of(), List.of(), status, null, unassessed, conflicts, diagnostics);
    }
}
