package com.example.oops.lexicon;

import com.example.oops.client.OpenAiClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Mocked response validation, not a real-model meaning or safety evaluation. */
class ContextValidatorTest {
    private final OpenAiClient client = mock(OpenAiClient.class);
    private final ContextValidator validator = new ContextValidator(client);
    private ContextValidator.Request request() {
        return new ContextValidator.Request(0, "수박", "특수 의미 가설", "음성 STT 대본",
                "저 사람을 평가한다", "저 사람 수박이네", "정치 이야기를 했다", "다른 출처 비밀 문구");
    }
    private ContextValidator.Verdict verdict(String meaning, String decision) {
        return new ContextValidator.Verdict(0, meaning, "REVIEW_REQUIRED".equals(decision) ? "저 사람" : "",
                "주변 정치 이야기와 해당 표현의 사용 관계를 확인한 모의 응답입니다.",
                decision, "수박",
                "REVIEW_REQUIRED".equals(decision) ? "정치적 특수 표현으로 원문의 저 사람을 낮춰 부르는 모의 표현입니다." : "",
                "UNCERTAIN".equals(decision) ? List.of("해당 표현을 설명하는 추가 문맥") : List.of());
    }
    private Map<Integer, ContextValidator.Verdict> run(List<ContextValidator.Verdict> rows) {
        when(client.isEnabled()).thenReturn(true);
        when(client.completeAsJson(anyString(), anyString(), eq(ContextValidator.BatchResult.class)))
                .thenReturn(Optional.of(new ContextValidator.BatchResult(rows)));
        return validator.validate(List.of(request()));
    }

    @Test void contextualMeaningAloneIsNotAReviewAndAmbiguityDoesNotPublish() {
        var pass = run(List.of(verdict("CONTEXTUAL", "PASS"))).get(0);
        assertThat(pass.isContextual()).isTrue();
        assertThat(pass.worthReporting()).isFalse();
        var ambiguous = run(List.of(verdict("AMBIGUOUS", "UNCERTAIN"))).get(0);
        assertThat(ambiguous.isAmbiguous()).isTrue();
        assertThat(ambiguous.worthReporting()).isFalse();
        assertThat(run(List.of(verdict("CONTEXTUAL", "REVIEW_REQUIRED"))).get(0).worthReporting()).isTrue();
    }

    @Test void duplicateIndexIncludingAThirdCopyFailsClosedRatherThanOverwriting() {
        var row = verdict("CONTEXTUAL", "REVIEW_REQUIRED");
        assertThat(run(List.of(row, row, row))).isEmpty();
    }

    @Test void unknownIndexesNullRowsAndMissingResultsAreNotImplicitPass() {
        var unknown = new ContextValidator.Verdict(8, "LITERAL", "", "일상 용법",
                "PASS", "수박", "", List.of());
        assertThat(run(Arrays.asList(null, unknown))).isEmpty();
        assertThat(run(List.of())).isEmpty();
    }

    @Test void inventedRawQuoteOrTargetIsRejected() {
        assertThat(run(List.of(new ContextValidator.Verdict(0, "CONTEXTUAL", "", "특수 맥락",
                "REVIEW_REQUIRED", "원문에 없는 표현", "현재 표현의 구체적인 문제를 설명하는 모의 이유입니다.", List.of())))).isEmpty();
        assertThat(run(List.of(new ContextValidator.Verdict(0, "CONTEXTUAL", "원문에 없는 대상", "특수 맥락",
                "REVIEW_REQUIRED", "수박", "현재 표현의 구체적인 문제를 설명하는 모의 이유입니다.", List.of())))).isEmpty();
    }

    @Test void invalidMeaningDecisionPairsAndLegacyIncompleteResponsesFailClosed() {
        for (var row : List.of(verdict("AMBIGUOUS", "REVIEW_REQUIRED"), verdict("LITERAL", "UNCERTAIN"),
                verdict("QUOTATION", "REVIEW_REQUIRED"),
                new ContextValidator.Verdict(0, "CONTEXTUAL", "", "기존 형식"))) {
            assertThat(run(List.of(row))).isEmpty();
        }
    }

    @Test void uncertaintyRequiresConcreteMissingInformationAndReviewRequiresReason() {
        assertThat(run(List.of(new ContextValidator.Verdict(0, "AMBIGUOUS", "", "의미 불명확",
                "UNCERTAIN", "수박", "", List.of())))).isEmpty();
        assertThat(run(List.of(new ContextValidator.Verdict(0, "CONTEXTUAL", "", "특수 의미",
                "REVIEW_REQUIRED", "수박", "", List.of())))).isEmpty();
    }

    @Test void userInputIsJsonEncodedAndDoesNotIncludeCrossSourceText() {
        run(List.of(verdict("CONTEXTUAL", "PASS")));
        var system = ArgumentCaptor.forClass(String.class);
        var user = ArgumentCaptor.forClass(String.class);
        verify(client).completeAsJson(system.capture(), user.capture(), eq(ContextValidator.BatchResult.class));
        var input = JsonMapper.builder().build().readTree(user.getValue());
        assertThat(input.get("items").get(0).get("line").asText()).isEqualTo(request().line());
        assertThat(user.getValue()).doesNotContain("다른 출처 비밀 문구", "relatedText");
        assertThat(system.getValue()).contains("정치적 입장·지지·밈", "QUOTATION", "STT와 OCR은 독립적으로 판단한다");
    }

    @Test void thirteenCandidatesStillUseTwoCallsWithoutASecondRiskJudge() {
        when(client.isEnabled()).thenReturn(true);
        when(client.completeAsJson(anyString(), anyString(), eq(ContextValidator.BatchResult.class)))
                .thenAnswer(call -> {
                    var items = JsonMapper.builder().build().readTree((String) call.getArgument(1)).get("items");
                    var rows = new ArrayList<ContextValidator.Verdict>();
                    for (var item : items) {
                        rows.add(new ContextValidator.Verdict(item.get("index").asInt(), "LITERAL", "",
                                "과일을 먹은 경험을 설명하는 일반 용법입니다.", "PASS", "수박", "", List.of()));
                    }
                    return Optional.of(new ContextValidator.BatchResult(rows));
                });
        var requests = java.util.stream.IntStream.range(0, 13).mapToObj(i ->
                new ContextValidator.Request(i, "수박", "가설", "음성 STT 대본",
                        null, "수박을 먹었다", null, null)).toList();
        assertThat(validator.validate(requests)).hasSize(13).allSatisfy((id, row) ->
                assertThat(row.worthReporting()).isFalse());
        verify(client, times(2)).completeAsJson(anyString(), anyString(), eq(ContextValidator.BatchResult.class));
    }
}
