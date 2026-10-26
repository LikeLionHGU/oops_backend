package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.assertThat;

class CasebookReferenceIsolationPreflightTest {
    @Test void canonicalFingerprintsIgnoreJsonObjectKeyOrderButPreserveMeaningfulOrder() throws Exception {
        String inputA = "{\"segments\":[{\"text\":\"same\",\"id\":\"s1\"}],\"genre\":\"GENERAL\"}";
        String inputB = "{\"genre\":\"GENERAL\",\"segments\":[{\"id\":\"s1\",\"text\":\"same\"}]}";
        assertThat(CasebookReferenceIsolationPreflight.canonicalInputSha(inputA))
                .isEqualTo(CasebookReferenceIsolationPreflight.canonicalInputSha(inputB));

        String prefix = "fixed prompt prefix";
        String systemA = prefix + "\ncontextReference={\"referenceContexts\":[{\"name\":\"example\",\"id\":\"case-1\"}],\"reviewGuidelines\":{\"b\":2,\"a\":1}}";
        String systemB = prefix + "\ncontextReference={\"reviewGuidelines\":{\"a\":1,\"b\":2},\"referenceContexts\":[{\"id\":\"case-1\",\"name\":\"example\"}]}";
        assertThat(CasebookReferenceIsolationPreflight.canonicalSystemSha(systemA))
                .isEqualTo(CasebookReferenceIsolationPreflight.canonicalSystemSha(systemB));
        assertThat(CasebookReferenceIsolationPreflight.canonicalSystemSha(systemA))
                .isNotEqualTo(CasebookReferenceIsolationPreflight.canonicalSystemSha(
                        "changed prompt prefix\n" + systemA.substring(prefix.length() + 1)));

        String reversedArray = "{\"genre\":\"GENERAL\",\"segments\":[{\"id\":\"s2\",\"text\":\"other\"},{\"id\":\"s1\",\"text\":\"same\"}]}";
        assertThat(CasebookReferenceIsolationPreflight.canonicalInputSha(inputA))
                .isNotEqualTo(CasebookReferenceIsolationPreflight.canonicalInputSha(reversedArray));

        String repairA = "fixed prompt\ncontextReference={\"referenceContexts\":[],\"reviewGuidelines\":{\"b\":2,\"a\":1}}\nrepair-only instruction";
        String repairB = "fixed prompt\ncontextReference={\"reviewGuidelines\":{\"a\":1,\"b\":2},\"referenceContexts\":[]}\nrepair-only instruction";
        assertThat(CasebookReferenceIsolationPreflight.canonicalSystemSha(repairA))
                .isEqualTo(CasebookReferenceIsolationPreflight.canonicalSystemSha(repairB));
        assertThat(CasebookReferenceIsolationPreflight.canonicalSystemSha(repairA))
                .isNotEqualTo(CasebookReferenceIsolationPreflight.canonicalSystemSha(
                        repairA.replace("repair-only instruction", "different repair instruction")));
    }

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
