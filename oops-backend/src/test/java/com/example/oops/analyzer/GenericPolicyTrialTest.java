package com.example.oops.analyzer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

/** Offline checks only: no provider call, no accuracy claim. */
class GenericPolicyTrialTest {
    private static final Path SUITE = Path.of("../datasets/controversy/evaluation/active-context-regression-20261018");
    private static final Path REVIEW_1102 = Path.of("../datasets/controversy/research/video-142-human-review-20261102.json");
    private static final Path REVIEW_1105 = Path.of("../datasets/controversy/research/video-142-human-review-20261105.json");
    private static final Path UNITS = Path.of("../datasets/controversy");

    /** Adopted in production on 2026-11-05: the shared prompts must now be exactly the measured treatment. */
    @Test void productionPromptsAreTheMeasuredGenericTreatment() {
        for (String word : new String[] {"주민", "매장", "메뉴", "가게", "음식"}) assertThat(CandidateReviewPrompts.POLICY).doesNotContain(word);
        for (var prompt : new String[] {CandidateReviewEngine.DISCOVERY_PROMPT, CandidateReviewEngine.VERIFICATION_PROMPT,
                CandidateReviewEngine.DISCOVERY_REPAIR_PROMPT}) {
            GenericPolicyTrial.POLICY_EDITS.forEach((removed, kept) -> {
                assertThat(prompt).doesNotContain(removed);
                if (!kept.isEmpty()) assertThat(prompt).contains(kept);
            });
            GenericPolicyTrial.STAGE_EDITS.keySet().forEach(removed -> assertThat(prompt).doesNotContain(removed));
        }
        assertThat(CandidateReviewEngine.DISCOVERY_PROMPT)
                .contains("  상황 설명만 대표로 고르지 말고 조롱·가치 절하를 구성하는 표현을 찾는다.\n");
        assertThat(CandidateReviewEngine.REVISION).contains("generic-policy");
        assertThat(CandidateReviewEngine.RELATION_REVISION).contains("generic-policy");
    }

    @Test void measuredTreatmentDiscoveryPromptIsTheProductionPrompt() throws Exception {
        Path plan = Path.of("../datasets/controversy/experiments/generic-policy-prepared-20261105/plan.json");
        Assumptions.assumeTrue(Files.isRegularFile(plan));
        var runs = ReviewGuidelineLibrary.JSON.readTree(Files.readAllBytes(plan)).path("runs");
        String marker = "contextReference=";
        String base = runs.get(0).path("initial").path("system").asText(), generic = runs.get(1).path("initial").path("system").asText();
        assertThat(runs.get(1).path("mode").asText()).isEqualTo("GENERIC_POLICY");
        String genericPrefix = generic.substring(0, generic.indexOf(marker));
        assertThat(genericPrefix).startsWith(CandidateReviewEngine.DISCOVERY_PROMPT);
        assertThat(base.substring(0, base.indexOf(marker))).endsWith(genericPrefix.substring(CandidateReviewEngine.DISCOVERY_PROMPT.length()))
                .doesNotStartWith(CandidateReviewEngine.DISCOVERY_PROMPT);
    }

    @Test void rewriteRefusesPromptsWithoutTheSharedPolicyOrWithDuplicates() {
        assertThatThrownBy(() -> GenericPolicyTrial.rewrite("정책 없음")).hasMessageContaining("Shared policy clause");
        String twice = CandidateReviewEngine.DISCOVERY_PROMPT + CandidateReviewEngine.DISCOVERY_PROMPT;
        assertThatThrownBy(() -> GenericPolicyTrial.rewrite(twice)).hasMessageContaining("Shared policy clause");
    }

    @Test void v1UnitsBecomeTwoPositivesAndFourSyntheticControls() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(UNITS.resolve("context-units/pisik-region.json")));
        var cases = GenericPolicyTrial.v1Cases(UNITS);
        assertThat(cases).extracting(ConfrontationStageTrial.Condition::id).containsExactly(
                "v1-region", "v1-menu", "v1-region-control-0", "v1-menu-control-0", "v1-life-choice-control-0", "v1-life-choice-control-1");
        assertThat(cases.get(0).raw()).extracting(ReviewInput.Segment::id)
                .containsExactly("stt-index-10", "stt-index-11", "stt-index-12", "stt-index-13", "stt-index-14",
                        "stt-index-15", "stt-index-16", "stt-index-17");
        assertThat(cases.get(1).raw()).extracting(ReviewInput.Segment::id).containsExactly("stt-index-45", "stt-index-46");
        assertThat(cases.get(0).seed().evidenceIds()).containsExactly("stt-index-17");
        assertThat(cases.get(1).seed().evidenceIds()).containsExactly("stt-index-45");
        assertThat(cases.subList(2, 6)).allSatisfy(c -> {
            assertThat(c.expectedDecision()).isEqualTo("PASS");
            assertThat(c.kind()).isEqualTo("SYNTHETIC_DEVELOPMENT_CONTROL");
            assertThat(c.seed().evidenceIds()).isEmpty();
        });
    }

    @Test void rewriteNowRefusesProductionPromptsBecauseTheTreatmentIsAlreadyApplied() {
        assertThatThrownBy(() -> GenericPolicyTrial.rewrite(CandidateReviewEngine.DISCOVERY_PROMPT))
                .hasMessageContaining("Shared policy clause");
    }
}
