package com.example.oops.analyzer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

/** Offline checks only: no provider call, no accuracy claim. */
class PrivacyScopeTrialTest {
    private static final Path SUITE = Path.of("../datasets/controversy/evaluation/active-context-regression-20261018");
    private static final Path RUNTIME = Path.of("../datasets/controversy/guidelines/runtime.json");
    private static final Path REVIEW_1102 = Path.of("../datasets/controversy/research/video-142-human-review-20261102.json");
    private static final Path REVIEW_1105 = Path.of("../datasets/controversy/research/video-142-human-review-20261105.json");

    private static JsonNode privacy(JsonNode runtime) {
        for (var g : runtime.path("guidelines")) if (g.path("id").asText().equals("privacy-exposure")) return g;
        throw new AssertionError("privacy-exposure missing");
    }

    @Test void expansionChangesOnlyThePrivacyGuidelineDefinition() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(RUNTIME));
        var production = (ObjectNode) JSON.readTree(Files.readAllBytes(RUNTIME));
        var expanded = (ObjectNode) JSON.readTree(PrivacyScopeTrial.expand(Files.readAllBytes(RUNTIME)));
        var changed = privacy(expanded);
        assertThat(changed.path("condition").asText()).isEqualTo(PrivacyScopeTrial.CONDITION).contains("사생활 주장");
        assertThat(changed.path("normalContrast").asText()).isEqualTo(PrivacyScopeTrial.CONTRAST).contains("자기 공개");
        assertThat(changed.path("requiredEvidence").asText()).isEqualTo(PrivacyScopeTrial.EVIDENCE);
        assertThat(changed.path("missingContext")).isEqualTo(privacy(production).path("missingContext"));
        for (String field : new String[] {"condition", "normalContrast", "requiredEvidence"}) {
            ((ObjectNode) privacy(expanded)).set(field, privacy(production).path(field));
        }
        assertThat(expanded).isEqualTo(production);
    }

    @Test void expansionRefusesARuntimeWhoseCurrentDefinitionDrifted() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(RUNTIME));
        var drifted = (ObjectNode) JSON.readTree(Files.readAllBytes(RUNTIME));
        ((ObjectNode) privacy(drifted)).put("condition", "다른 정의");
        assertThatThrownBy(() -> PrivacyScopeTrial.expand(JSON.writeValueAsBytes(drifted)))
                .hasMessageContaining("Current privacy definition required");
    }

    @Test void reviewRecordBecomesOnePositiveAndTwoPassWindows() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(REVIEW_1105));
        var cases = PrivacyScopeTrial.reviewed(Files.readAllBytes(REVIEW_1105));
        assertThat(cases).extracting(ConfrontationStageTrial.Condition::id).containsExactly(
                "142-third-party-romance-claim", "142-introduction-table-comment", "142-marital-conflict-story");
        assertThat(cases).extracting(ConfrontationStageTrial.Condition::expectedDecision)
                .containsExactly("REVIEW_REQUIRED", "PASS", "PASS");
        assertThat(cases.get(0).seed().evidenceIds()).contains("stt-index-178", "stt-index-188");
        assertThat(cases.get(1).seed().evidenceIds()).isEmpty();
        assertThat(cases).allSatisfy(c -> assertThat(c.raw()).isNotEmpty().hasSizeLessThanOrEqualTo(64));
    }

    @Test void axesRewriteAddsThirdPartyPrivateLifeExactlyOnce() {
        String rewritten = PrivacyScopeTrial.rewriteAxes(CandidateReviewEngine.DISCOVERY_PROMPT);
        assertThat(rewritten).isEqualTo(CandidateReviewEngine.DISCOVERY_PROMPT.replace(PrivacyScopeTrial.AXES_FROM, PrivacyScopeTrial.AXES_TO))
                .contains("사생활(연애·건강·가족사) 폭로");
        assertThat(PrivacyScopeTrial.rewriteAxes(CandidateReviewEngine.verificationPrompt(3))).contains(PrivacyScopeTrial.AXES_TO);
        assertThatThrownBy(() -> PrivacyScopeTrial.rewriteAxes("축 없음")).hasMessageContaining("Exactly one axis clause");
        assertThatThrownBy(() -> PrivacyScopeTrial.rewriteAxes(rewritten + rewritten)).hasMessageContaining("Exactly one axis clause");
    }

    @Test void definitionOnlyPreparationDiffersOnlyInThePrivacyGuideline(@TempDir Path dir) throws Exception {
        assertPairs(dir, PrivacyScopeTrial.SCOPED);
    }

    @Test void axesPreparationAlsoRewritesOnlyTheAxisClause(@TempDir Path dir) throws Exception {
        assertPairs(dir, PrivacyScopeTrial.AXES_MODE);
    }

    private static void assertPairs(Path dir, String treatment) throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(SUITE.resolve("inputs.json")) && Files.isRegularFile(REVIEW_1105));
        Path variant = dir.resolve("runtime-privacy-scope.json");
        Files.write(variant, PrivacyScopeTrial.expand(Files.readAllBytes(RUNTIME)));
        var plan = PrivacyScopeTrial.prepare(SUITE.resolve("inputs.json"), SUITE.resolve("labels.json"), REVIEW_1102, REVIEW_1105,
                variant, treatment);
        assertThat(plan.treatment()).isEqualTo(treatment);
        assertThat(plan.cases()).hasSize(16);
        assertThat(plan.cases().stream().filter(c -> c.expectedDecision().equals("REVIEW_REQUIRED")).count()).isEqualTo(6);
        assertThat(plan.runs()).hasSize(32);
        assertThat(plan.maxReservation()).isLessThanOrEqualTo(PrivacyScopeTrial.BUDGET);
        int changedReferences = 0;
        String marker = "contextReference=";
        for (int i = 0; i < plan.runs().size(); i += 2) {
            var base = plan.runs().get(i).initial(); var scoped = plan.runs().get(i + 1).initial();
            assertThat(plan.runs().get(i).mode()).isEqualTo("BASE");
            assertThat(plan.runs().get(i + 1).mode()).isEqualTo(treatment);
            assertThat(scoped.input()).isEqualTo(base.input());
            String basePrefix = base.system().substring(0, base.system().indexOf(marker));
            String scopedPrefix = scoped.system().substring(0, scoped.system().indexOf(marker));
            assertThat(scopedPrefix).isEqualTo(treatment.equals(PrivacyScopeTrial.AXES_MODE) ? PrivacyScopeTrial.rewriteAxes(basePrefix) : basePrefix);
            var baseRef = (ObjectNode) JSON.readTree(base.system().substring(base.system().indexOf(marker) + marker.length()));
            var scopedRef = (ObjectNode) JSON.readTree(scoped.system().substring(scoped.system().indexOf(marker) + marker.length()));
            assertThat(scopedRef.path("referenceContexts")).isEqualTo(baseRef.path("referenceContexts"));
            assertThat(privacy(scopedRef.set("guidelines", scopedRef.path("reviewGuidelines"))).path("condition").asText())
                    .isEqualTo(PrivacyScopeTrial.CONDITION);
            if (!scopedRef.path("reviewGuidelines").equals(baseRef.path("reviewGuidelines"))) changedReferences++;
        }
        assertThat(changedReferences).isEqualTo(16);
        assertThat(new String(JSON.writeValueAsBytes(plan), java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("OPENAI_API_KEY");
    }
}
