package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OopsProperties;
import com.example.oops.domain.*;
import com.example.oops.news.NewsSearchClient;
import com.example.oops.news.SourceClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 서버 실영상 테스트(배우 인터뷰 28분) 뒤 바꾼 사실 확인·배경 확인 기준을 잠근다. */
class FactCheckTuningTest {

    private OpenAiClient openAi;
    private NewsSearchClient news;
    private final Video video = Video.builder().sourceType(SourceType.YOUTUBE).build();

    @BeforeEach
    void setUp() {
        openAi = mock(OpenAiClient.class);
        when(openAi.isEnabled()).thenReturn(true);
        news = mock(NewsSearchClient.class);
        when(news.isEnabled()).thenReturn(true);
        when(news.providerName()).thenReturn("test");
        List<NewsSearchClient.NewsItem> items = List.of(
                new NewsSearchClient.NewsItem("기사 제목", "기사 본문", "2026-10-01", "https://news.example.kr/a?id=1", "언론"),
                new NewsSearchClient.NewsItem("기사 제목2", "기사 본문2", "2026-10-02", "https://news.example.kr/a?id=2", "언론"));
        when(news.searchArchive(anyString(), anyInt())).thenReturn(items);
        when(news.searchRecent(anyString(), anyInt())).thenReturn(items);
    }

    private AnalysisContext context(String... speech) {
        List<TranscriptSegment> t = new java.util.ArrayList<>();
        for (int i = 0; i < speech.length; i++) t.add(new TranscriptSegment(video, i * 3000L, i * 3000L + 2500, speech[i]));
        return new AnalysisContext(video, ContentGenre.TALK_PODCAST, t, List.of());
    }

    private EntityCheckAnalyzer entityCheck() {
        OopsProperties props = new OopsProperties(null, new OopsProperties.Analysis(List.of(), false, "srt", null));
        return new EntityCheckAnalyzer(openAi, List.of(news), new SourceClassifier(), props);
    }

    private void claims(int n) {
        List<EntityCheckAnalyzer.Claim> list = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) list.add(new EntityCheckAnalyzer.Claim(i, "주장" + i, "대상", "DATE", List.of("검색어" + i)));
        when(openAi.completeAsJson(anyString(), anyString(), eq(EntityCheckAnalyzer.ClaimResult.class)))
                .thenReturn(Optional.of(new EntityCheckAnalyzer.ClaimResult(list)));
    }

    @Test
    @DisplayName("사실 확인: 자료와 분명히 다른 FACT_ERROR 만 카드가 된다")
    void onlyConfidentFactErrors() {
        claims(4);
        when(openAi.completeAsJson(anyString(), contains("주장0"), eq(EntityCheckAnalyzer.Verdict.class)))
                .thenReturn(Optional.of(new EntityCheckAnalyzer.Verdict("UNVERIFIED_CLAIM", 0.9, "자료에서 확인되지 않습니다.", "자료에는 다른 이야기가 나옵니다", List.of(0))));
        when(openAi.completeAsJson(anyString(), contains("주장1"), eq(EntityCheckAnalyzer.Verdict.class)))
                .thenReturn(Optional.of(new EntityCheckAnalyzer.Verdict("FACT_ERROR", 0.6, "영상에서는 2010년이라 했는데 자료에는 2009년으로 나옵니다.", "2009년", List.of(0))));
        when(openAi.completeAsJson(anyString(), contains("주장2"), eq(EntityCheckAnalyzer.Verdict.class)))
                .thenReturn(Optional.of(new EntityCheckAnalyzer.Verdict("FACT_ERROR", 0.9, "영상에서는 2010년이라 했는데 자료에는 2009년으로 나옵니다.", "2009년", List.of())));
        when(openAi.completeAsJson(anyString(), contains("주장3"), eq(EntityCheckAnalyzer.Verdict.class)))
                .thenReturn(Optional.of(new EntityCheckAnalyzer.Verdict("FACT_ERROR", 0.9, "영상에서는 2010년이라 했는데 자료에는 2009년으로 나옵니다.", "2009년 데뷔", List.of(0))));

        List<RiskFinding> out = entityCheck().analyze(context("첫 줄", "둘째 줄", "셋째 줄", "넷째 줄"));
        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.getCategory()).isEqualTo(RiskCategory.FACT_ERROR);
            assertThat(f.getText()).isEqualTo("넷째 줄");
        });
        // 대조할 때 앞뒤 대화를 함께 준다
        verify(openAi).completeAsJson(anyString(), contains("▶ (발언) 셋째 줄"), eq(EntityCheckAnalyzer.Verdict.class));
    }

    @Test
    @DisplayName("배경 확인: 영상의 말과 무관(related=false)하거나 점수가 0.75 미만이면 버린다")
    void contextCheckNeedsRelatedAndScore() {
        ContextCheckAnalyzer analyzer = new ContextCheckAnalyzer(openAi, List.of(news));
        when(openAi.completeAsJson(anyString(), anyString(), eq(ContextCheckAnalyzer.TopicResult.class)))
                .thenReturn(Optional.of(new ContextCheckAnalyzer.TopicResult(List.of(
                        new ContextCheckAnalyzer.Topic(0, "조보아 배우", "같이 찍은 배우"),
                        new ContextCheckAnalyzer.Topic(1, "정혜성 인터뷰", "본인 인터뷰"),
                        new ContextCheckAnalyzer.Topic(2, "OO역 참사", "참사 언급")))));
        when(openAi.completeAsJson(anyString(), contains("등장한 주제: 조보아 배우"), eq(ContextCheckAnalyzer.Judgement.class)))
                .thenReturn(Optional.of(new ContextCheckAnalyzer.Judgement(false, true, 0.9, "캐스팅 보도", "캐스팅", List.of(0))));
        when(openAi.completeAsJson(anyString(), contains("등장한 주제: 정혜성 인터뷰"), eq(ContextCheckAnalyzer.Judgement.class)))
                .thenReturn(Optional.of(new ContextCheckAnalyzer.Judgement(true, true, 0.7, "근황", "근황", List.of(0))));
        when(openAi.completeAsJson(anyString(), contains("등장한 주제: OO역 참사"), eq(ContextCheckAnalyzer.Judgement.class)))
                .thenReturn(Optional.of(new ContextCheckAnalyzer.Judgement(true, true, 0.85, "희생자 유족 관련 보도가 이어지고 있습니다.", "OO역 참사 1주기", List.of(0))));

        List<RiskFinding> out = analyzer.analyze(context("조보아 배우랑 같이 찍었어요", "정혜성 인터뷰 때 그랬죠", "OO역 참사 그거 웃기던데"));
        assertThat(out).singleElement().satisfies(f -> assertThat(f.getText()).isEqualTo("OO역 참사 그거 웃기던데"));
    }

    @Test
    @DisplayName("'해당 배우' 같은 분류명 대상은 이름이 아니다")
    void haedangIsPlaceholder() {
        assertThat(SpeechReviewAnalyzer.isPlaceholderTarget("해당 배우")).isTrue();
        assertThat(SpeechReviewAnalyzer.isPlaceholderTarget("정혜성")).isFalse();
    }
}
