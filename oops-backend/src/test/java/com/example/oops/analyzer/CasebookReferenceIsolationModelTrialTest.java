package com.example.oops.analyzer;

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
                Map.of(), 40, 4, 120_000, 8_000, 15_000, .70, .644096);
        CasebookReferenceIsolationModelTrial.validate(plan);
        var wrongModel = new CasebookReferenceIsolationModelTrial.Plan(
                plan.schemaVersion(), plan.scope(), plan.sourceCaseId(), plan.expectedDecisionLocalOnly(),
                plan.sourceSegmentIds(), plan.reviewSha256(), plan.transcriptSha256(), plan.runtimeSha256(),
                plan.offlinePreflightSha256(), plan.implementationSha256(), plan.implementation(), "other-model",
                plan.reasoningEffort(), plan.repeatsPerCondition(), plan.conditionOrder(), plan.conditions(),
                plan.expectedDiscoveryFingerprints(), plan.maxHttpAttemptsTotal(), plan.maxHttpAttemptsPerAnalysis(),
                plan.maxRequestBodyBytes(), plan.maxOutputTokens(), plan.spacingBetweenAnalysesMs(), plan.hardCapUsd(),
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
}
