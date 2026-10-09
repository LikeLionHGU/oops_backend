package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline contract tests only; no claims about semantic recall or real-model accuracy. */
class DialogueContractPromptTest {
    @Test void relationshipAndDecisionAreSeparateAndCurrentRestrictionsRemainExplicit() {
        assertThat(TextReviewEngine.DIALOGUE_CONTRACT).contains(
                "먼저 인용 가능한 원문 사이에 어떤 관계가 있는지 확인하고",
                "같은 대상의 정상 리뷰도 이 관계의 PASS",
                "PASS 또는 GRAPHIC_METAPHOR의 REVIEW_REQUIRED만 가능하다.",
                "공격 대상 없는 REVIEW_REQUIRED는 CONNECTED_EXPRESSION/GRAPHIC_METAPHOR에만 허용된다.",
                "limited는 문맥이 제한됐다는 표시이지 자동 보류 조건이 아니다.");
        for (var relation : DialogueReview.Relation.values()) {
            assertThat(TextReviewEngine.DIALOGUE_CONTRACT).contains(relation.name());
        }
    }

    @Test void twoDistinctSegmentsAndRepresentativeAssessmentDoNotImplyFullCoverage() {
        assertThat(TextReviewEngine.DIALOGUE_CONTRACT).contains(
                "같은 구간을 PRIMARY/TARGET으로 두 번 기록해도 서로 다른 구간 2개가 되지 않는다.",
                "두 인용의 역할을 reason에서 설명한다.",
                "현재 계약은 묶음당 하나의 assessment만 받는다.",
                "원문과 대상 연결이 가장 명확한 문제를 대표로 선택한다.",
                "전체 묶음의 모든 문제를 검토 완료한 것으로 만들지 않는다.",
                "구체적인 검토 이유가 성립한 대표 문제가 있으면 다른 부분의 불확실성만으로 지우지 않는다.");
    }

    @Test void passExampleParsesAndPassesActualIsolatedDialogueValidation() {
        var mapper = JsonMapper.builder().build();
        String prompt = TextReviewEngine.DIALOGUE_CONTRACT;
        int start = prompt.indexOf("{\"unitEvaluations\":");
        int end = prompt.indexOf("\n\n## 6.", start);
        var example = mapper.readValue(prompt.substring(start, end), TextReviewEngine.LlmResult.class);
        var assessment = example.unitEvaluations().get(0).assessment();
        var input = new ReviewInput(List.of(
                new ReviewInput.Segment("example-primary", TimelineEventType.SPEECH, 0, 1000,
                        "이 제품은 가격이 비싸다", null),
                new ReviewInput.Segment("example-context", TimelineEventType.SPEECH, 2000, 3000,
                        "기능은 충분하다", null)));
        var context = new AnalysisContext(Video.builder().filename("example.mp4").build(),
                ContentGenre.GENERAL, List.of(), List.of(), input);
        var client = mock(OpenAiClient.class);
        when(client.completeAsJson(anyString(), anyString(), eq(TextReviewEngine.LlmResult.class)))
                .thenAnswer(call -> {
                    var user = mapper.readTree((String) call.getArgument(1));
                    if (user.get("reviewMode") != null) {
                        String id = user.get("requiredUnitIds").get(0).asText();
                        return Optional.of(new TextReviewEngine.LlmResult(null, List.of(
                                new DialogueReview.LlmDecision(id, "SAME_TARGET_CONNECTED", assessment))));
                    }
                    var passes = input.segments().stream().map(s -> new TextReviewEngine.LlmDecision(
                            s.id(), "PASS", s.text(), "제품의 가격과 기능을 설명하는 일반적인 리뷰입니다.",
                            null, null, null, null, null, List.<String>of())).toList();
                    return Optional.of(new TextReviewEngine.LlmResult(passes));
                });
        var analyzer = new SpeechReviewAnalyzer(client);
        assertThat(analyzer.analyze(context)).isEmpty();
        var result = analyzer.consumeReviewResult(context).orElseThrow();
        assertThat(result.diagnostics().dialogue().invalidAttempts()).isZero();
        assertThat(result.diagnostics().dialogue().assessed()).isEqualTo(1);
        assertThat(result.diagnostics().dialogue().units().get(0).state()).isEqualTo(ReviewDiagnostics.State.PASS);
        assertThat(result.unassessedSegmentIds()).isEmpty();
    }
}
