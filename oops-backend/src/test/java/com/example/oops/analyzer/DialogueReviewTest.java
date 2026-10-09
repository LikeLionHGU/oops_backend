package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.stream.IntStream;
import static com.example.oops.analyzer.TextReviewEngine.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Simulated contract responses; does not demonstrate real-model recall or false-positive rate. */
class DialogueReviewTest {
    private final OpenAiClient client = mock(OpenAiClient.class);
    private final SpeechReviewAnalyzer analyzer = new SpeechReviewAnalyzer(client, false); // previous window-only contract
    private final Video video = Video.builder().filename("dialogue.mp4").build();
    private AnalysisContext context() {
        return new AnalysisContext(video, ContentGenre.GENERAL, IntStream.range(0, 13).mapToObj(i ->
                new TranscriptSegment(video, i * 5000, i * 5000 + 1000,
                        i == 1 ? "이 지역 주민들은" : i == 6 ? "그런 것도 못 먹는 사람들답네" : "대화" + i)).toList(), null);
    }
    private LlmDecision candidate(boolean bridge) {
        var evidence = new ArrayList<>(List.of(
                new LlmEvidence("stt-index-6", "그런 것도 못 먹는 사람들답네", "PRIMARY"),
                new LlmEvidence("stt-index-1", "이 지역 주민들은", "TARGET")));
        if (bridge) evidence.add(new LlmEvidence("stt-index-11", "대화11", "CONTEXT"));
        return new LlmDecision("stt-index-6", "REVIEW_REQUIRED", "그런 것도 못 먹는 사람들답네",
                "앞서 지칭한 지역 주민을 음식 선택지가 없다는 이유로 낮춰 부르는 연결 발언입니다.",
                "BELITTLEMENT", "지역 주민", 0.6, null, null, List.of(), evidence,
                "RESIDENT_GROUP", "CONTEXTUAL", "앞서 지역 주민을 지칭한 발언에 뒤의 평가를 연결합니다.",
                "음식 선택에 대한 상황 설명일 수 있으므로 실제 대상과 어조 확인이 필요합니다.");
    }
    private LlmDecision pass(int index, String text) {
        return new LlmDecision("stt-index-" + index, "PASS", text, "현재 문맥에서 별도의 구체적인 검토 이유가 없는 발언입니다.",
                null, null, null, null, null, List.of());
    }
    private void respond(boolean bridge) {
        var context = context();
        var decisions = IntStream.range(0, 13).mapToObj(i -> i == 6 ? candidate(bridge)
                : pass(i, context.transcript().get(i).getText())).toList();
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class)))
                .thenReturn(Optional.of(new LlmResult(decisions)));
    }
    @Test void earlierTargetOutsideCentredWindowIsValidInOneTrailingWindow() {
        respond(false);
        var context = context();
        assertThat(analyzer.analyze(context)).hasSize(1);
        var result = analyzer.consumeReviewResult(context).orElseThrow();
        assertThat(result.unassessedSegmentIds()).isEmpty();
        assertThat(result.diagnostics().failureCounts()).isEmpty();
        verify(client).completeAsJson(argThat(s -> s.contains("제공된 발언을 시간순으로 읽고 대상·상황·평가의 연결을 확인한다")
                && s.contains("대상 식별 근거, 상황 설명, 문제 표현을 구분한다")),
                argThat(s -> s.contains("dialogueUnits") && s.contains("TRAILING")), eq(LlmResult.class));
    }
    @Test void cannotCombineOppositeViewsIntoAnOverlongEvidenceChain() {
        respond(true);
        var context = context();
        assertThat(analyzer.analyze(context)).isEmpty();
        var result = analyzer.consumeReviewResult(context).orElseThrow();
        assertThat(result.unassessedSegmentIds()).contains("stt-index-6");
        assertThat(result.diagnostics().failureCounts()).containsKey(ReviewDiagnostics.Failure.EVIDENCE_OUTSIDE_WINDOW);
    }
    @ParameterizedTest
    @ValueSource(strings = {
            "이 동네에는 매장이 없어요|집에서 만들어 먹어요|제 입에는 담백해요",
            "새우를 못 먹어요|대신 버섯을 넣었어요|찌개 같은 느낌이에요",
            "메뉴가 여러 개네요|주는 대로 먹어봤어요|제 취향에는 안 맞아요"
    })
    void normalDeprivationSubstitutionAndPersonalReviewsRemainPassWhenModelReturnsPass(String lines) {
        String[] text = lines.split("\\|");
        var context = new AnalysisContext(video, ContentGenre.GENERAL, IntStream.range(0, text.length)
                .mapToObj(i -> new TranscriptSegment(video, i * 2000, i * 2000 + 1000, text[i])).toList(), null);
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class))).thenReturn(Optional.of(
                new LlmResult(IntStream.range(0, text.length).mapToObj(i -> pass(i, text[i])).toList())));
        assertThat(analyzer.analyze(context)).isEmpty();
        assertThat(analyzer.consumeReviewResult(context).orElseThrow().status()).isEqualTo(AnalyzerStatus.SUCCESS);
        verify(client).completeAsJson(anyString(), anyString(), eq(LlmResult.class));
    }
}
