package com.example.oops.analyzer;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;

class CasebookHoldoutCurrentPipelineTrialTest {
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

    @Test void offlinePreflightPinsCurrentRuntimeRetrievalAndMakesNoProviderCalls() throws Exception {
        var manifest = CasebookHoldoutCurrentPipelineTrial.preflight(CasebookHoldoutCurrentPipelineTrial.prepare());
        assertThat(manifest.path("providerCalls").asInt()).isZero();
        assertThat(manifest.path("analyses").asInt()).isEqualTo(6);
        assertThat(manifest.path("cases").isArray()).isTrue();
        assertThat(manifest.path("cases").size()).isEqualTo(3);
        assertThat(manifest.path("mediaEgress").asBoolean()).isFalse();
        assertThat(manifest.path("cases").toString()).doesNotContain("2년 내내 점심을 안 먹고");
    }

    @Test void worstCaseReservationStaysWithinFreshHalfDollarApproval() {
        assertThat(CasebookHoldoutCurrentPipelineTrial.reservationEstimate())
                .isCloseTo(.3864576, within(1e-12));
        assertThat(CasebookHoldoutCurrentPipelineTrial.reservationEstimate())
                .isLessThan(CasebookHoldoutCurrentPipelineTrial.CAP_USD);
        assertThat(CasebookHoldoutCurrentPipelineTrial.MAX_HTTP_TOTAL).isEqualTo(24);
        assertThat(CasebookHoldoutCurrentPipelineTrial.MAX_HTTP_PER_ANALYSIS).isEqualTo(4);
    }

    @Test void noExpectedDecisionCanEnterAnOpenAiUserPayload() throws Exception {
        var userPayload = JSON.readTree("{\"raw\":[{\"id\":\"stt-index-1\",\"text\":\"a sample transcript\"}]}");
        assertThat(SocialDefectCurrentModelTrial.Guard.hasForbiddenLabel(userPayload)).isFalse();
        assertThat(SocialDefectCurrentModelTrial.Guard.hasForbiddenLabel(
                JSON.readTree("{\"expectedDecision\":\"PASS\",\"raw\":[]}"))).isTrue();
    }
}
