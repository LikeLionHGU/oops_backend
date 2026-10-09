package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Request contract checks only; no real-model calls or accuracy claims. */
class SegmentContractPromptTest {
    @Test void scopeAndCoverageAreSeparateFromIndependentDialogueAssessment() {
        assertThat(TextReviewEngine.CONTRACT).contains(
                "독립 대화 묶음 판정은 별도 요청에서 수행한다.",
                "모든 ID에 최소 한 개의 evaluations 항목을 반환한다.",
                "context에만 있는 ID를 evaluations의 segmentId로 반환하지 않는다.",
                "개수만 맞추고 다른 ID를 누락해도 된다는 뜻이 아니다.",
                "코드 블록·추가 설명·추론 과정·unitEvaluations를 출력하지 않는다.")
                .doesNotContain("여기 매장이 있나요?", "한계를 느꼈다",
                        "모르는 단어를 건강·정치·커뮤니티 용어", "PASS 또는 REVIEW_REQUIRED 또는 UNCERTAIN");
    }

    @Test void limitedContextAndDeduplicationCannotAutomaticallySuppressReview() {
        assertThat(TextReviewEngine.CONTRACT).contains(
                "contextLimited/limited는 제공 문맥이 제한됐다는 표시이지 자동 보류 조건이 아니다.",
                "독립적인 문제 표현을 PASS 처리하지 마라.",
                "후보 병합은 서버가 수행한다.",
                "대표 anchor의 같은 출처 근거는 reviewUnits 또는 dialogueUnits 중 하나의 창 안에서 연결한다.",
                "화면 글자 요청의 판단·PRIMARY/TARGET/CONTEXT 인용은 제공된 CAPTION 원문만 사용한다.",
                "같은 segmentId에 PASS와 다른 결정을 함께 반환하지 않았는지 확인한다.");
    }

    @Test void literalPassExampleParsesAndPassesExistingEvidenceValidation() {
        String contract = TextReviewEngine.CONTRACT;
        int start = contract.indexOf("{\"evaluations\":");
        int end = contract.indexOf("\n\n## 5.", start);
        var response = JsonMapper.builder().build().readValue(contract.substring(start, end),
                TextReviewEngine.LlmResult.class);
        var item = response.evaluations().get(0);
        assertThat(item.decision()).isEqualTo("PASS");
        assertThat(item.category()).isNull();
        assertThat(item.score()).isNull();
        assertThat(item.evidenceText()).isEqualTo(item.evidence().get(0).quote());
        var input = new ReviewInput(List.of(new ReviewInput.Segment(item.segmentId(),
                TimelineEventType.SPEECH, 0, 1000, item.evidenceText(), null)));
        var context = new AnalysisContext(Video.builder().filename("example.mp4").build(),
                ContentGenre.GENERAL, List.of(), List.of(), input);
        var client = mock(OpenAiClient.class);
        when(client.completeAsJson(anyString(), anyString(), eq(TextReviewEngine.LlmResult.class)))
                .thenReturn(Optional.of(response));
        var analyzer = new SpeechReviewAnalyzer(client, false);
        assertThat(analyzer.analyze(context)).isEmpty();
        var result = analyzer.consumeReviewResult(context).orElseThrow();
        assertThat(result.status()).isEqualTo(AnalyzerStatus.SUCCESS);
        assertThat(result.unassessedSegmentIds()).isEmpty();
        assertThat(result.diagnostics().failureCounts()).isEmpty();
    }
}
