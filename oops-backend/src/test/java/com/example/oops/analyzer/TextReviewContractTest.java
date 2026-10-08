package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import static com.example.oops.analyzer.TextReviewEngine.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Output contract regression tests, not real-model quality evaluations. */
class TextReviewContractTest {
    private final OpenAiClient client = mock(OpenAiClient.class);
    private final Video video = Video.builder().filename("contract.mp4").build();
    private AnalysisContext context() {
        return new AnalysisContext(video, ContentGenre.GENERAL, List.of(
                new TranscriptSegment(video, 0, 1000, "이 가게는 메뉴를 고를 의미가 없어요")), null);
    }
    private LlmDecision candidate(String evidenceText, String type) {
        return new LlmDecision("stt-index-0", "REVIEW_REQUIRED", evidenceText,
                "가게의 메뉴 선택에 의미가 없다고 단정하여 운영과 메뉴 구성을 부정적으로 평가하는 발언입니다.",
                "STRONG_NEGATIVE_REVIEW", "이 가게", 0.6, null, null, List.of(),
                List.of(new LlmEvidence("stt-index-0", "메뉴를 고를 의미가 없어요", "PRIMARY"),
                        new LlmEvidence("stt-index-0", "이 가게", "TARGET")),
                type, "EXPLICIT", "대표 발언이 이 가게의 메뉴를 직접 지목하여 평가하고 있습니다.",
                "개인적인 메뉴 선택 경험에 대한 리뷰일 수 있어 실제 운영 맥락을 확인해야 합니다.");
    }
    private Result run() {
        return TextReviewEngine.run(client, context(), TimelineEventType.SPEECH, "contract-test", "JSON 검토",
                Set.of(RiskCategory.STRONG_NEGATIVE_REVIEW), 0);
    }
    @ParameterizedTest
    @EnumSource(ReviewEvaluation.TargetType.class)
    void publishedTargetTypesCannotDriftFromValidatorEnum(ReviewEvaluation.TargetType type) {
        assertThat(CONTRACT).contains(type.name());
        assertThat(CONTRACT).doesNotContain("{{TARGET_TYPES}}");
    }
    @Test void sameAnchorTargetAndMatchingPrimaryProduceGroundedCandidate() {
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class)))
                .thenReturn(Optional.of(new LlmResult(List.of(candidate("메뉴를 고를 의미가 없어요", "BUSINESS")))));
        var result = run();
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).getTarget()).isEqualTo("이 가게");
        assertThat(result.diagnostics().failureCounts()).isEmpty();
        assertThat(result.status()).isEqualTo(AnalyzerStatus.SUCCESS);
        verify(client).completeAsJson(argThat(prompt -> prompt.contains("BUSINESS=가게·기업")
                && prompt.contains("PRIMARY quote와 글자·공백·문장부호까지 완전히 같아야 한다")),
                anyString(), eq(LlmResult.class));
    }
    @Test void rawQuotesThatAreIndividuallyValidButDisagreeStillFailClosed() {
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class)))
                .thenReturn(Optional.of(new LlmResult(List.of(candidate("고를 의미가 없어요", "BUSINESS")))));
        var result = run();
        assertThat(result.findings()).isEmpty();
        assertThat(result.diagnostics().failureCounts()).containsEntry(ReviewDiagnostics.Failure.PRIMARY_QUOTE_MISMATCH, 2);
        assertThat(result.diagnostics().segments().get(0).state()).isEqualTo(ReviewDiagnostics.State.REJECTED);
    }
    @Test void rejectedCandidateCanRecoverWithoutBeingConvertedToPass() {
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class)))
                .thenReturn(Optional.of(new LlmResult(List.of(candidate("고를 의미가 없어요", "BUSINESS")))),
                        Optional.of(new LlmResult(List.of(candidate("메뉴를 고를 의미가 없어요", "BUSINESS")))));
        var result = run();
        assertThat(result.findings()).hasSize(1);
        assertThat(result.diagnostics().segments().get(0).state()).isEqualTo(ReviewDiagnostics.State.REVIEW_REQUIRED);
        assertThat(result.diagnostics().failureCounts()).containsEntry(ReviewDiagnostics.Failure.PRIMARY_QUOTE_MISMATCH, 1);
    }
    @Test void inventedTargetTypeRemainsRejectedDespiteHelpfulPrompt() {
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class)))
                .thenReturn(Optional.of(new LlmResult(List.of(candidate("메뉴를 고를 의미가 없어요", "RESTAURANT")))));
        var result = run();
        assertThat(result.findings()).isEmpty();
        assertThat(result.diagnostics().failureCounts()).containsEntry(ReviewDiagnostics.Failure.INVALID_TARGET_TYPE, 2);
    }
}
