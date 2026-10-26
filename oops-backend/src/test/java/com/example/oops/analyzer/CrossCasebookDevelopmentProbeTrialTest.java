package com.example.oops.analyzer;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
    }

    @Test void requestGuardRejectsUnapprovedEndpointAndMultimodalOrLabeledPayload() throws Exception {
        var output = java.nio.file.Files.createTempDirectory("cross-casebook-guard-");
        try {
            var guard = new CrossCasebookDevelopmentProbeTrial.Guard(output);
            guard.beginAnalysis();
            byte[] request = com.example.oops.analyzer.ReviewGuidelineLibrary.JSON.writeValueAsBytes(
                    java.util.Map.of("model", "gpt-6-luna", "reasoning_effort", "none", "max_completion_tokens", 8_000,
                            "messages", java.util.List.of(java.util.Map.of("role", "system", "content", "rules"),
                                    java.util.Map.of("role", "user", "content", "{\"text\":\"hello\"}"))));
            assertThat(guard.accept(request, CrossCasebookDevelopmentProbeTrial.ENDPOINT)).isNotEmpty();

            guard.beginAnalysis();
            byte[] labelRequest = com.example.oops.analyzer.ReviewGuidelineLibrary.JSON.writeValueAsBytes(
                    java.util.Map.of("model", "gpt-6-luna", "reasoning_effort", "none", "max_completion_tokens", 8_000,
                            "messages", java.util.List.of(java.util.Map.of("role", "system", "content", "rules"),
                                    java.util.Map.of("role", "user", "content", "{\"expectedDecision\":\"PASS\"}"))));
            assertThat(org.assertj.core.api.Assertions.catchThrowable(
                    () -> guard.accept(labelRequest, CrossCasebookDevelopmentProbeTrial.ENDPOINT)))
                    .isInstanceOf(IllegalArgumentException.class);

            guard.beginAnalysis();
            byte[] mediaRequest = com.example.oops.analyzer.ReviewGuidelineLibrary.JSON.writeValueAsBytes(
                    java.util.Map.of("model", "gpt-6-luna", "reasoning_effort", "none", "max_completion_tokens", 8_000,
                            "messages", java.util.List.of(java.util.Map.of("role", "system", "content", "rules"),
                                    java.util.Map.of("role", "user", "content", "{\"image_url\":\"data:image/png;base64,x\"}"))));
            assertThat(org.assertj.core.api.Assertions.catchThrowable(
                    () -> guard.accept(mediaRequest, CrossCasebookDevelopmentProbeTrial.ENDPOINT)))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            java.nio.file.Files.deleteIfExists(output.resolve("reservation.json"));
            java.nio.file.Files.deleteIfExists(output);
        }
    }
}
