package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.assertThat;

class CasebookReferenceIsolationPreflightTest {
    @Test void preflightUsesOnlyTheFrozenSelectedReferenceAndNoExampleArms() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/evals/casebook-reference-isolation-plan.json")) {
            assertThat(input).isNotNull();
            var plan = JSON.readTree(input);
            assertThat(plan.path("status").asText())
                    .isEqualTo("DESIGN_ONLY_FRESH_APPROVAL_REQUIRED_FOR_MODEL_CALLS");
            assertThat(plan.at("/conditions/0/id").asText()).isEqualTo("CURRENT_SELECTED_REFERENCE");
            assertThat(plan.at("/conditions/0/guidelineExamplesMaxCases").asInt()).isEqualTo(3);
            assertThat(plan.at("/conditions/0/expectedSelection/0").asText())
                    .isEqualTo("podcast-dev#podcast-boast-response");
            assertThat(plan.at("/conditions/1/id").asText()).isEqualTo("GLOBAL_GUIDELINES_WITHOUT_EXAMPLES");
            assertThat(plan.at("/conditions/1/guidelineExamplesMaxCases").asInt()).isZero();
            assertThat(plan.at("/protocol/freshApprovalRequired").asBoolean()).isTrue();
            assertThat(plan.path("target").has("expectedDecision")).isFalse();
        }
    }
}
