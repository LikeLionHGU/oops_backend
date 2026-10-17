package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.ReviewGuidelineLibrary.JSON;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Wiring/scope tests with synthetic responses, never a model comprehension benchmark. */
class SituationFirstVerificationTest {
    @Test void replacesReadingPriorityWithoutChangingPolicySchemaOrFrozenSuffix() {
        for (String baseline : List.of(verificationPrompt(0), verificationPrompt(8), SourceRelationPrompts.verification(0),
                FinalReviewBasisContract.prompt(verificationPrompt(0)))) {
            String frozen = "\n고정 참고:\n" + verificationPrompt(0);
            String changed = SituationFirstVerification.prompt(baseline + frozen);
            int boundary = baseline.indexOf("# 대상 필드 형식 예시");
            assertThat(changed).startsWith(CandidateReviewPrompts.POLICY).endsWith(baseline.substring(boundary) + frozen);
            assertThat(changed).contains("뜻풀이·인용 속 질문", "자기 설명·자기 제안", "별도 사고 과정·새 JSON 필드는 반환하지 않는다");
            assertThat(changed.substring(0, changed.indexOf("# 대상 필드 형식 예시")))
                    .doesNotContain("먼저 proposedEvidence의 평가");
            assertThat(changed.substring(changed.indexOf("JSON: {\"verifications\"")))
                    .isEqualTo((baseline + frozen).substring(baseline.indexOf("JSON: {\"verifications\"")));
        }
    }

    @Test void unknownOrAlreadyTransformedPromptsFailClosedEvenWhenSuffixContainsLegacyText() {
        assertThatThrownBy(() -> SituationFirstVerification.prompt("unknown")).isInstanceOf(IllegalArgumentException.class);
        String changed = SituationFirstVerification.prompt(verificationPrompt(0));
        assertThatThrownBy(() -> SituationFirstVerification.prompt(changed + verificationPrompt(0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    List<String> requests(boolean enabled) {
        var f = new CandidateReviewEngineTest();
        f.discovery(List.of("stt-index-0"), f.proposal("stt-index-0", "그 집"));
        f.verification(new Verification("candidate-1", f.assessment("stt-index-0", "PASS", "그 집")));
        var result = run(f.client, f.context("그 집은 조용하다"), 24, null, null, null, null, 0, false, null, false, enabled);
        assertThat(result.status().name()).isEqualTo("SUCCESS");
        assertThat(result.diagnostics().candidatePipeline().revision())
                .isEqualTo(REVISION + (enabled ? "+" + SituationFirstVerification.VERSION : ""));
        var system = org.mockito.ArgumentCaptor.forClass(String.class);
        var input = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(f.client, times(1)).completeAsJson(system.capture(), input.capture(), eq(Discovery.class));
        verify(f.client, times(1)).completeAsJson(system.capture(), input.capture(), eq(VerificationResult.class));
        return List.of(system.getAllValues().get(0), input.getAllValues().get(0),
                system.getAllValues().get(1), input.getAllValues().get(1));
    }

    @Test void flagChangesVerificationSystemAndRevisionButNotSourcePayloadOrCallCount() {
        var baseline = requests(false); var changed = requests(true);
        assertThat(changed.get(0)).isEqualTo(baseline.get(0));
        assertThat(changed.get(2)).isEqualTo(SituationFirstVerification.prompt(baseline.get(2)));
        for (int index : List.of(1, 3)) {
            var before = (tools.jackson.databind.node.ObjectNode) JSON.readTree(baseline.get(index));
            var after = (tools.jackson.databind.node.ObjectNode) JSON.readTree(changed.get(index));
            assertThat(after.path("promptRevision").asText()).isEqualTo(before.path("promptRevision").asText()
                    + (index == 1 ? "+" + SituationFirstVerification.VERSION : ""));
            before.remove("promptRevision"); after.remove("promptRevision");
            assertThat(after).isEqualTo(before);
        }
    }

    @Test void repairUsesSameReadingOrderAndCannotChangeRawOrCandidate() {
        var f = new CandidateReviewEngineTest();
        f.discovery(List.of("stt-index-0"), f.proposal("stt-index-0", "그 집"));
        var good = f.assessment("stt-index-0", "PASS", "그 집");
        var inputs = new ArrayList<tools.jackson.databind.JsonNode>();
        when(f.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class))).thenAnswer(call -> {
            assertThat((String) call.getArgument(0)).contains("# 상황 이해 → 비판 해석 → 정상 해석 대조");
            inputs.add(JSON.readTree((String) call.getArgument(1)));
            return Optional.of(new VerificationResult(List.of(new Verification("candidate-1", inputs.size() == 1
                    ? new CandidateReferenceRepairTest().badId(good) : good))));
        });
        var result = run(f.client, f.context("그 집은 조용하다"), 24, null, null, null, null, 0, false, null, false, true);
        assertThat(result.status().name()).isEqualTo("SUCCESS");
        assertThat(inputs).hasSize(2);
        assertThat(inputs.get(0).path("raw")).isEqualTo(inputs.get(1).path("raw"));
        assertThat(inputs.get(0).path("candidates")).isEqualTo(inputs.get(1).path("candidates"));
        assertThat(result.diagnostics().candidatePipeline().repairCalls()).isOne();
    }

    @Test void analyzerFlagReachesVerificationAndDoesNotForceReviewToPass() {
        var f = new CandidateReviewEngineTest();
        f.discovery(List.of("stt-index-0"), f.proposal("stt-index-0", "그 집"));
        when(f.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class))).thenAnswer(call -> {
            assertThat((String) call.getArgument(0)).contains("# 상황 이해");
            return Optional.of(new VerificationResult(List.of(new Verification("candidate-1",
                    f.assessment("stt-index-0", "REVIEW_REQUIRED", "그 집")))));
        });
        var analyzer = new SpeechReviewAnalyzer(f.client, true, true, 24);
        analyzer.setSituationFirstEnabled(true);
        var context = f.context("그 집 이용자는 수준이 낮아");
        assertThat(analyzer.analyze(context)).hasSize(1);
        assertThat(analyzer.consumeReviewResult(context).orElseThrow().diagnostics().candidatePipeline().revision())
                .contains(SituationFirstVerification.VERSION);
    }

    @Test void basisContractAndSituationFirstCanBeEnabledTogetherWithoutBypassingTargetValidation() {
        var f = new FinalReviewBasisContractTest();
        f.f.discovery(List.of(f.id), f.proposal("TARGET_TREATMENT"));
        when(f.f.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class))).thenAnswer(call -> {
            assertThat((String) call.getArgument(0)).contains("# 상황 이해", "assessment 밖의 reviewBasis");
            return Optional.of(new VerificationResult(List.of(new Verification("candidate-1", f.expression("SEXUAL"),
                    f.basis("TARGET_TREATMENT")))));
        });
        var result = run(f.f.client, f.context, 24, null, null, null, null, 0, false, null, true, true);
        assertThat(result.status().name()).isEqualTo("PARTIAL");
        assertThat(result.findings()).isEmpty();
        assertThat(result.diagnostics().candidatePipeline().candidates()).anySatisfy(t ->
                assertThat(t.failureCode()).isEqualTo("TARGET_REQUIRED"));
    }
}
