package com.example.oops.analyzer;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("dataset-backed")
class CrossCasebookDevelopmentProbeTrialTest {
    @Test void freezesThreeLabelFreeCasesAndExactApprovedBoundsWithoutProviderCalls() throws Exception {
        var plan = CrossCasebookDevelopmentProbeTrial.prepare();
        var manifest = CrossCasebookDevelopmentProbeTrial.manifest(plan);

        assertThat(plan.analyses()).isEqualTo(6);
        assertThat(plan.maxHttpAttempts()).isEqualTo(24);
        assertThat(plan.maxHttpPerAnalysis()).isEqualTo(4);
        assertThat(plan.capUsd()).isEqualTo(.55);
        assertThat(plan.worstCaseReservedUsd()).isCloseTo(.3864576,
                org.assertj.core.data.Offset.offset(1e-9));
        assertThat(plan.cases()).extracting(CrossCasebookDevelopmentProbeTrial.CaseInput::id)
                .containsExactly("DEV-01", "DEV-02", "DEV-03");
        assertThat(manifest.path("providerCalls").asInt()).isZero();
        assertThat(manifest.path("mediaEgress").asBoolean()).isFalse();
        assertThat(manifest.path("expectedLabelsInModelInputs").asBoolean()).isFalse();
        assertThat(manifest.path("conditions")).hasSize(6);
        assertThat(plan.sourceHashes().keySet()).contains("transcript:DEV-01", "transcript:DEV-02", "transcript:DEV-03");
        assertThat(plan.implementationHashes().keySet()).contains(
                "src/test/java/com/example/oops/analyzer/CasebookReferenceIsolationPreflight.java",
                "src/test/java/com/example/oops/analyzer/MechanismRoutingTrial.java",
                "src/test/java/com/example/oops/analyzer/DefaultVerificationTrial.java");
    }

    @Test void changedMaterializedTextCannotPassTheFrozenSourceSelector() throws Exception {
        byte[] selectors = java.nio.file.Files.readAllBytes(CrossCasebookDevelopmentProbeTrial.SELECTORS);
        var inputs = (ObjectNode) com.example.oops.analyzer.ReviewGuidelineLibrary.JSON.readTree(
                java.nio.file.Files.readAllBytes(CrossCasebookDevelopmentProbeTrial.INPUTS));
        ((ObjectNode) inputs.path("items").get(1).path("segments").get(2)).put("text", "changed text");
        assertThatThrownBy(() -> CrossCasebookDevelopmentProbeTrial.validateInputBindings(
                selectors, com.example.oops.analyzer.ReviewGuidelineLibrary.JSON.writeValueAsBytes(inputs)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Materialized text/time does not match");
    }

    @Test void anyImplementationOrSourceHashDriftInvalidatesTheFrozenPlan() throws Exception {
        var plan = CrossCasebookDevelopmentProbeTrial.prepare();
        var changedHashes = new java.util.TreeMap<>(plan.implementationHashes());
        changedHashes.put("src/main/java/com/example/oops/analyzer/CandidateReviewEngine.java", "changed");
        var drifted = new CrossCasebookDevelopmentProbeTrial.Plan(plan.schemaVersion(), plan.scope(),
                plan.consentToken(), plan.model(), plan.reasoningEffort(), plan.analyses(), plan.maxHttpAttempts(),
                plan.maxHttpPerAnalysis(), plan.maxRequestBodyBytes(), plan.maxOutputTokens(), plan.spacingMs(),
                plan.capUsd(), plan.worstCaseReservedUsd(), plan.sourceHashes(), changedHashes,
                plan.arms(), plan.cases());
        assertThatThrownBy(() -> CrossCasebookDevelopmentProbeTrial.assertSameFrozenPlan(plan, drifted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("drift");
    }

    @Test void requestGuardRejectsEndpointMediaLabelsLimitsAndDuplicateRetries() throws Exception {
        var output = java.nio.file.Files.createTempDirectory("cross-casebook-guard-");
        try {
            var guard = new CrossCasebookDevelopmentProbeTrial.Guard(output);
            guard.beginAnalysis();
            byte[] request = requestBody("{\"text\":\"hello\"}", 8_000);
            assertThat(guard.accept(request, CrossCasebookDevelopmentProbeTrial.ENDPOINT)).isNotEmpty();
            assertThatThrownBy(() -> guard.accept(request, CrossCasebookDevelopmentProbeTrial.ENDPOINT))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate/retry");

            guard.beginAnalysis();
            assertThatThrownBy(() -> guard.accept(request, "https://example.invalid/chat/completions"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("endpoint");

            guard.beginAnalysis();
            assertThatThrownBy(() -> guard.accept(requestBody("{\"expectedDecision\":\"PASS\"}", 8_000),
                    CrossCasebookDevelopmentProbeTrial.ENDPOINT))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Expected labels");

            guard.beginAnalysis();
            assertThatThrownBy(() -> guard.accept(requestBody("{\"image_url\":\"data:image/png;base64,x\"}", 8_000),
                    CrossCasebookDevelopmentProbeTrial.ENDPOINT))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Media payload");

            guard.beginAnalysis();
            assertThatThrownBy(() -> guard.accept(requestBody("{}", 8_001), CrossCasebookDevelopmentProbeTrial.ENDPOINT))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Output/media");

            guard.beginAnalysis();
            assertThatThrownBy(() -> guard.accept(requestBody("x".repeat(CrossCasebookDevelopmentProbeTrial.MAX_BODY_BYTES), 8_000),
                    CrossCasebookDevelopmentProbeTrial.ENDPOINT))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("byte cap");

            guard.beginAnalysis(); guard.totalAttempts = CrossCasebookDevelopmentProbeTrial.MAX_HTTP_TOTAL;
            assertThatThrownBy(() -> guard.accept(request, CrossCasebookDevelopmentProbeTrial.ENDPOINT))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("request limit");

            guard.totalAttempts = 0; guard.beginAnalysis();
            guard.analysisAttempts = CrossCasebookDevelopmentProbeTrial.MAX_HTTP_PER_ANALYSIS;
            assertThatThrownBy(() -> guard.accept(request, CrossCasebookDevelopmentProbeTrial.ENDPOINT))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("request limit");

            guard.beginAnalysis(); guard.reservedUsd = CrossCasebookDevelopmentProbeTrial.CAP_USD;
            assertThatThrownBy(() -> guard.accept(request, CrossCasebookDevelopmentProbeTrial.ENDPOINT))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reservation cap");
        } finally {
            java.nio.file.Files.deleteIfExists(output.resolve("reservation.json"));
            java.nio.file.Files.deleteIfExists(output);
        }
    }

    private static byte[] requestBody(String userContent, int maxOutputTokens) throws Exception {
        return com.example.oops.analyzer.ReviewGuidelineLibrary.JSON.writeValueAsBytes(
                java.util.Map.of("model", "gpt-6-luna", "reasoning_effort", "none",
                        "max_completion_tokens", maxOutputTokens,
                        "messages", java.util.List.of(java.util.Map.of("role", "system", "content", "rules"),
                                java.util.Map.of("role", "user", "content", userContent))));
    }
}
