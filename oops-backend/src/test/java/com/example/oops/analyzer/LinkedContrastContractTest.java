package com.example.oops.analyzer;

import org.junit.jupiter.api.Test;
import java.util.List;
import static com.example.oops.analyzer.CandidateReviewEngine.*;
import static com.example.oops.analyzer.TextReviewEngine.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Optional;

/** Contract tests, not a claim of model accuracy or deterministic decisions. */
class LinkedContrastContractTest {
    private final CandidateReviewEngineTest fixture = new CandidateReviewEngineTest();

    private LlmDecision pass(String alternative, String contextQuote) {
        var evidence = contextQuote == null
                ? List.of(new LlmEvidence("stt-index-0", "店はない", "PRIMARY"))
                : List.of(new LlmEvidence("stt-index-0", "店はない", "PRIMARY"),
                        new LlmEvidence("stt-index-1", contextQuote, "CONTEXT"));
        return new LlmDecision("stt-index-0", "PASS", "店はない", "自分の買い物の代替案を説明している。",
                null, null, null, null, null, List.of(), evidence,
                null, null, null, alternative);
    }

    private Validation check(LlmDecision decision, String axis, boolean linked) {
        var input = fixture.context("店はない", "自分で作る").reviewInput();
        var quotes = linked ? List.of(new Quote("stt-index-0", "店はない"), new Quote("stt-index-1", "自分で作る"))
                : List.of(new Quote("stt-index-0", "店はない"));
        return validate(new Candidate("candidate-1", new Proposal("stt-index-0", axis,
                "生活の価値を下げている可能性", quotes), input.segments(), false, false), decision, input);
    }

    @Test void linkedPassRequiresContrastButNeverBecomesWarningAutomatically() {
        assertThat(check(pass(null, "自分で作る"), "TARGET_TREATMENT", true).failureCode())
                .isEqualTo("LINKED_PASS_CONTRAST_REQUIRED");
    }
    @Test void linkedPassRequiresProposedNonAnchorContext() {
        assertThat(check(pass("他人の生活を軽視する解釈もあるが、本人の選択しか述べていない。", null),
                "TARGET_TREATMENT", true).failureCode()).isEqualTo("LINKED_PASS_CONTEXT_REQUIRED");
    }
    @Test void groundedNormalSubstitutionRemainsPass() {
        var result = check(pass("他人の生活を軽視する解釈もあるが、本人の選択しか述べていない。", "自分で作る"),
                "TARGET_TREATMENT", true);
        assertThat(result.failureCode()).isNull();
        assertThat(result.observation().decision().name()).isEqualTo("PASS");
    }
    @Test void FabricatedContextCannotSatisfyContract() {
        assertThat(check(pass("対照解釈", "住民は劣っている"), "TARGET_TREATMENT", true).failureCode())
                .isEqualTo("DECISION_QUOTE_NOT_IN_RAW");
    }
    @Test void SingleQuoteAndExpressionPassDoNotAcquireLinkedTargetRequirement() {
        assertThat(check(pass(null, null), "TARGET_TREATMENT", false).failureCode()).isNull();
        assertThat(check(pass(null, null), "EXPRESSION_CONTENT", true).failureCode()).isNull();
    }
    @Test void TextAndVisualRepairsShareNeutralCorrection() {
        assertThat(repairPrompt("LINKED_PASS_CONTEXT_REQUIRED")).isEqualTo(CONTRAST_REPAIR_PROMPT);
        assertThat(repairPrompt("LINKED_PASS_CONTRAST_REQUIRED")).isEqualTo(CONTRAST_REPAIR_PROMPT);
        assertThat(CONTRAST_REPAIR_PROMPT).contains("PASS", "UNCERTAIN", "경고를 만들 의무가 없다");
    }
    @Test void MissingContrastCanRepairToPassWithoutPublishingWarning() {
        var context = fixture.context("그 집 가게가 없어", "내가 직접 만들었어");
        fixture.discovery(List.of("stt-index-0", "stt-index-1"),
                new Proposal("stt-index-0", "TARGET_TREATMENT", "선택지 연결 확인",
                        List.of(new Quote("stt-index-0", "그 집"), new Quote("stt-index-1", "직접 만들었어"))));
        when(fixture.client.completeAsJson(anyString(), anyString(), eq(VerificationResult.class)))
                .thenReturn(Optional.of(new VerificationResult(List.of(new Verification("candidate-1",
                                fixture.assessment("stt-index-0", "PASS", "그 집"))))),
                        Optional.of(new VerificationResult(List.of(new Verification("candidate-1",
                                fixture.linkedPass("stt-index-0", "그 집", "stt-index-1", "직접 만들었어"))))));
        var result = run(fixture.client, context, 24);
        assertThat(result.findings()).isEmpty();
        assertThat(result.diagnostics().candidatePipeline().repairCalls()).isOne();
        assertThat(result.diagnostics().segments()).anySatisfy(s ->
                assertThat(s.decisions()).anySatisfy(d -> assertThat(d.decision().name()).isEqualTo("PASS")));
        verify(fixture.client).completeAsJson(contains(CONTRAST_REPAIR_PROMPT), contains("LINKED_PASS_CONTRAST_REQUIRED"), eq(VerificationResult.class));
    }
    @Test void PersistentMissingContrastIsNotAcceptedAsPassOrConvertedToWarning() {
        var context = fixture.context("그 집 가게가 없어", "내가 직접 만들었어");
        fixture.discovery(List.of("stt-index-0", "stt-index-1"),
                new Proposal("stt-index-0", "TARGET_TREATMENT", "선택지 연결 확인",
                        List.of(new Quote("stt-index-0", "그 집"), new Quote("stt-index-1", "직접 만들었어"))));
        fixture.verification(new Verification("candidate-1", fixture.assessment("stt-index-0", "PASS", "그 집")));
        var result = run(fixture.client, context, 24);
        assertThat(result.findings()).isEmpty();
        assertThat(result.diagnostics().candidatePipeline().repairCalls()).isOne();
        assertThat(result.diagnostics().candidatePipeline().verificationFailed()).isOne();
        verify(fixture.client, times(2)).completeAsJson(anyString(), anyString(), eq(VerificationResult.class));
    }
}
