package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import com.example.oops.news.NewsSearchClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContextCheckAnalyzerTest {
    private final OpenAiClient client = mock(OpenAiClient.class);
    private final NewsSearchClient newsClient = mock(NewsSearchClient.class);
    private final ContextCheckAnalyzer analyzer = new ContextCheckAnalyzer(client, List.of(newsClient));
    private final Video video = Video.builder().filename("v1-regression.mp4").build();
    // Synthetic article metadata only: these fixtures do not assert that a real event occurred.
    private final NewsSearchClient.NewsItem article = new NewsSearchClient.NewsItem(
            "롯데리아 회동 관련 수사", "회동 관련 수사가 진행됐다", "2026-10-01", "https://example.com/news", "fixture");

    private AnalysisContext context(String text) {
        return new AnalysisContext(video, null, List.of(new TranscriptSegment(video, 0, 1_000, text)), null);
    }

    private void stub(String keyword, NewsSearchClient.NewsItem news, ContextCheckAnalyzer.Judgement judgement) {
        when(newsClient.isEnabled()).thenReturn(true);
        when(newsClient.searchRecent(eq(keyword), anyInt())).thenReturn(List.of(news));
        when(client.completeAsJson(anyString(), anyString(), eq(ContextCheckAnalyzer.TopicResult.class)))
                .thenReturn(Optional.of(new ContextCheckAnalyzer.TopicResult(List.of(
                        new ContextCheckAnalyzer.Topic(0, keyword, "추출 모델이 만든 사건 관련 맥락")))));
        when(client.completeAsJson(anyString(), anyString(), eq(ContextCheckAnalyzer.Judgement.class)))
                .thenReturn(Optional.of(judgement));
    }

    private ContextCheckAnalyzer.Judgement judgement(String relation, String videoQuote, String newsQuote,
                                                     List<Integer> sources, List<String> terms) {
        return new ContextCheckAnalyzer.Judgement(true, 0.95, "제공된 기사 제목에 회동 관련 수사가 언급되어 있습니다.",
                "회동 관련 수사", sources, relation, videoQuote, newsQuote, terms,
                "영상에서 기사 속 회동 사건을 직접 지칭하며 평가하고 있습니다.");
    }

    @Test
    void mereBrandMentionNeverBecomesTimingWarningEvenWithHighScore() {
        stub("롯데리아", article, judgement("MERE_MENTION", "롯데리아 없어", "롯데리아 회동", List.of(0), List.of("롯데리아")));
        assertThat(analyzer.analyze(context("롯데리아 없어"))).isEmpty();
    }

    @Test
    void claimedDirectLinkWithOnlySameBrandIsRejected() {
        stub("롯데리아", article, judgement("DIRECT_EVENT", "롯데리아 없어", "롯데리아 회동", List.of(0), List.of("롯데리아")));
        assertThat(analyzer.analyze(context("롯데리아 없어"))).isEmpty();
    }

    @Test
    void generalFoodMentionDoesNotBecomeHealthNewsWarning() {
        var news = new NewsSearchClient.NewsItem("패스트푸드 소비 연구", "건강 연구 결과", null, "https://example.com/food");
        stub("패스트푸드", news, judgement("DIRECT_EVENT", "몸에 안 좋은 패스트푸드", "패스트푸드 소비 연구", List.of(0), List.of("패스트푸드")));
        assertThat(analyzer.analyze(context("몸에 안 좋은 패스트푸드 집에서 먹지 말고"))).isEmpty();
    }

    @Test
    void groundedEventGetsActualInputAndReferencesButNotHighSeverity() {
        stub("롯데리아", article, judgement("DIRECT_EVENT", "롯데리아 회동", "롯데리아 회동", List.of(0), List.of("회동")));
        var results = analyzer.analyze(context("롯데리아 회동 관련 수사를 이야기하자"));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(results.get(0).getReferences()).hasSize(1);
        assertThat(results.get(0).getReason()).contains("기사 제목·요약 기준", "연결 근거");
        var prompts = ArgumentCaptor.forClass(String.class);
        verify(client).completeAsJson(anyString(), prompts.capture(), eq(ContextCheckAnalyzer.Judgement.class));
        assertThat(prompts.getValue()).contains("롯데리아 회동 관련 수사를 이야기하자", "근거 아님");
    }

    @Test
    void missingInvalidReferencesAndFabricatedQuotesAreNotReplacedWithFallbackArticles() {
        for (var result : List.of(
                judgement("DIRECT_EVENT", "롯데리아 회동", "롯데리아 회동", List.of(), List.of("회동")),
                judgement("DIRECT_EVENT", "롯데리아 회동", "롯데리아 회동", List.of(7), List.of("회동")),
                judgement("DIRECT_EVENT", "영상에 없는 회동 발언", "롯데리아 회동", List.of(0), List.of("회동")),
                judgement("DIRECT_EVENT", "롯데리아 회동", "기사에 없는 회동 설명", List.of(0), List.of("회동")))) {
            stub("롯데리아", article, result);
            assertThat(analyzer.analyze(context("롯데리아 회동 관련 수사"))).isEmpty();
        }
    }

    @Test
    void nonFiniteScoreAndMissingRelationContractAreRejected() {
        for (var result : List.of(
                new ContextCheckAnalyzer.Judgement(true, Double.NaN, "기사에서 회동 관련 수사를 구체적으로 설명합니다.",
                        "회동 관련 수사", List.of(0), "DIRECT_EVENT", "롯데리아 회동", "롯데리아 회동", List.of("회동"),
                        "영상이 기사 속 회동 사건을 직접 지칭하고 있습니다."),
                new ContextCheckAnalyzer.Judgement(true, 0.9, "기사에서 회동 관련 수사를 구체적으로 설명합니다.",
                        "회동 관련 수사", List.of(0), null, null, null, null, null))) {
            stub("롯데리아", article, result);
            assertThat(analyzer.analyze(context("롯데리아 회동 관련 수사"))).isEmpty();
        }
    }

    @Test
    void editorialFrameIsNotSerializedIntoModelInput() {
        stub("롯데리아", article, judgement("DIRECT_EVENT", "롯데리아 회동", "롯데리아 회동", List.of(0), List.of("회동")));
        var frame = new VideoFrame(video, 0, "frames/private-path.jpg", "image/jpeg");
        var text = new ScreenText(video, 0, 1_000, "롯데리아 회동 관련 수사", 0.9, frame);
        text.classify(ScreenTextRole.EDITORIAL, "편집 자막");
        assertThat(analyzer.analyze(new AnalysisContext(video, null, null, List.of(text)))).hasSize(1);
        var prompts = ArgumentCaptor.forClass(String.class);
        verify(client).completeAsJson(anyString(), prompts.capture(), eq(ContextCheckAnalyzer.Judgement.class));
        assertThat(prompts.getValue()).contains("롯데리아 회동 관련 수사").doesNotContain("private-path", "storageKey");
    }
}
