package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import com.example.oops.fusion.FindingFusionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 1차 실영상 테스트(5번 영상) 뒤 바꾼 발언·자막 비교 기준을 잠근다. */
class CaptionMismatchTuningTest {

    private final Video video = Video.builder().sourceType(SourceType.UPLOAD).build();

    @Test
    @DisplayName("모델이 MINOR 로 본 것, MAJOR 라도 점수가 낮은 것, 유형이 없는 것은 카드가 되지 않는다")
    void onlyMajorDifferencesSurvive() {
        OpenAiClient openAi = mock(OpenAiClient.class);
        when(openAi.isEnabled()).thenReturn(true);
        when(openAi.completeAsJson(eq(CaptionMismatchAnalyzer.SYSTEM_PROMPT), anyString(),
                eq(CaptionMismatchAnalyzer.LlmResult.class)))
                .thenReturn(Optional.of(new CaptionMismatchAnalyzer.LlmResult(List.of(
                        new CaptionMismatchAnalyzer.LlmFinding(0, "MINOR", "DISTORTED", 0.9, "발언은 '그냥' 인데 자막은 '내가' 로 되어 있습니다."),
                        new CaptionMismatchAnalyzer.LlmFinding(1, "MAJOR", "DISTORTED", 0.4, "발언은 A 인데 자막은 B 로 되어 있어 다르게 읽힙니다."),
                        new CaptionMismatchAnalyzer.LlmFinding(2, "MAJOR", "PROVOCATIVE", 0.85, "발언에 없던 '미친놈' 이라는 비하가 자막에서 생겨 출연자를 조롱하는 말로 읽힙니다."),
                        new CaptionMismatchAnalyzer.LlmFinding(3, "MAJOR", null, 0.9, "발언은 A 인데 자막은 B 로 되어 있어 다르게 읽힙니다.")))));

        List<TranscriptSegment> t = List.of(
                new TranscriptSegment(video, 0, 2000, "그냥 할머니 맛이다"),
                new TranscriptSegment(video, 3000, 5000, "이거 십만원이야"),
                new TranscriptSegment(video, 6000, 8000, "이 사람 진짜 웃기네"),
                new TranscriptSegment(video, 9000, 11000, "오늘은 여기까지"));
        List<ScreenText> c = List.of(
                new ScreenText(video, 0, 2000, "내가 할머니 맛이다", 1.0, null),
                new ScreenText(video, 3000, 5000, "이거 만원이야", 1.0, null),
                new ScreenText(video, 6000, 8000, "이 사람 진짜 미친놈이네", 1.0, null),
                new ScreenText(video, 9000, 11000, "다음 시간에 계속", 1.0, null));

        List<RiskFinding> out = new CaptionMismatchAnalyzer(openAi)
                .analyze(new AnalysisContext(video, ContentGenre.GENERAL, t, c));
        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.getCaptionText()).isEqualTo("이 사람 진짜 미친놈이네");
            assertThat(f.getReason()).startsWith("(발언과 자막 차이 · 표현 강화)");
        });
        // 프롬프트에 앞뒤 자막이 같이 들어간다
        verify(openAi).completeAsJson(eq(CaptionMismatchAnalyzer.SYSTEM_PROMPT),
                contains("앞 자막: 이거 만원이야"), eq(CaptionMismatchAnalyzer.LlmResult.class));
    }

    @Test
    @DisplayName("시간이 거의 안 겹치는 자막은 앞 발언에 붙이지 않는다 (다음 사람 말 자막)")
    void captionNeedsRealTimeOverlap() {
        List<TranscriptSegment> t = List.of(new TranscriptSegment(video, 62_500, 64_500, "그래도 맛은 참 건강하다"));
        ScreenText same = new ScreenText(video, 62_500, 64_500, "그래도 맛은 참 건강하다", 1.0, null);
        ScreenText next = new ScreenText(video, 64_500, 65_000, "뭐 시장을 한 번 가볼래요?", 1.0, null);
        AnalysisContext ctx = new AnalysisContext(video, ContentGenre.GENERAL, t, List.of(same, next));
        assertThat(new CaptionMismatchAnalyzer(null).matchByTime(ctx)).isEmpty();
    }

    private RiskFinding mismatch(long start, String caption) {
        return RiskFinding.builder().video(video).eventType(TimelineEventType.CAPTION)
                .category(RiskCategory.CAPTION_MISMATCH).source(EvidenceSource.SUBTITLE)
                .score(0.8).startMs(start).endMs(start + 1500)
                .speechText("발언").captionText(caption)
                .reason("발언은 A 인데 자막은 B 로 되어 있어 다르게 읽힙니다.").build();
    }

    @Test
    @DisplayName("발언·자막 차이 카드는 붙어 있어도 자막이 다르면 한 장으로 뭉치지 않는다")
    void mismatchCardsStaySeparate() {
        List<RiskFinding> out = new FindingFusionService().fuse(List.of(
                mismatch(16_500, "반찬 투정하면 안 된다"),
                mismatch(18_500, "하나만 얘기할게 여기는..."),
                mismatch(20_500, "아니 산나물 먹기 싫다")));
        assertThat(out).hasSize(3);
    }
}
