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
                .thenAnswer(invocation -> {
                    var input = tools.jackson.databind.json.JsonMapper.builder().build()
                            .readTree((String) invocation.getArgument(1));
                    var line = input.get("lines").get(0);
                    return Optional.of(new ContextCheckAnalyzer.TopicResult(List.of(
                            new ContextCheckAnalyzer.Topic(0, keyword, "추출 모델이 만든 사건 관련 맥락",
                                    line.get("source").asText(),
                                    List.of(new ContextCheckAnalyzer.TopicEvidence(0, line.get("text").asText())),
                                    List.of(keyword), "구체적인 사건 단서를 확인한다"))));
                });
        when(client.completeAsJson(anyString(), anyString(), eq(ContextCheckAnalyzer.Judgement.class)))
                .thenReturn(Optional.of(judgement));
    }

    private ContextCheckAnalyzer.Judgement judgement(String relation, String videoQuote, String newsQuote,
                                                     List<Integer> sources, List<String> terms) {
        boolean connected = List.of("DIRECT_EVENT", "CONTEXTUAL_EVENT").contains(relation);
        return new ContextCheckAnalyzer.Judgement(connected ? "NOTICE" : "NO_NOTICE",
                connected ? 0.95 : null, "제공된 기사 제목에 회동 관련 수사가 언급되어 있습니다.",
                "회동 관련 수사", sources, relation, 0, videoQuote,
                sources.isEmpty() ? List.of() : List.of(new ContextCheckAnalyzer.SourceQuote(sources.get(0), newsQuote)),
                "CURRENT_DEVELOPMENT", "영상에서 기사 속 회동 사건을 직접 지칭하며 평가하고 있습니다.",
                connected ? "회동 수사의 최근 진행 상황이 영상의 사건 설명에 반영됐는지 확인하세요." : null,
                List.of());
    }

    @Test
    void mereBrandMentionNeverBecomesTimingWarningEvenWithHighScore() {
        stub("롯데리아", article, judgement("MERE_MENTION", "롯데리아 없어", "롯데리아 회동", List.of(0), List.of("롯데리아")));
        assertThat(analyzer.analyze(context("롯데리아 없어"))).isEmpty();
    }

    @Test
    void sameBrandMentionIsExplicitNoNoticeNotSourceFailure() {
        stub("롯데리아", article, judgement("MERE_MENTION", "롯데리아 없어", "롯데리아 회동", List.of(0), List.of("롯데리아")));
        assertThat(analyzer.analyze(context("롯데리아 없어"))).isEmpty();
        assertThat(analyzer.consumeCoverageNotice(null)).isEmpty();
    }

    @Test
    void generalFoodMentionDoesNotBecomeHealthNewsWarning() {
        var news = new NewsSearchClient.NewsItem("패스트푸드 소비 연구", "건강 연구 결과", null, "https://example.com/food");
        stub("패스트푸드", news, judgement("MERE_MENTION", "몸에 안 좋은 패스트푸드", "패스트푸드 소비 연구", List.of(0), List.of("패스트푸드")));
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
        var base = judgement("DIRECT_EVENT", "롯데리아 회동", "롯데리아 회동", List.of(0), List.of("회동"));
        for (var result : List.of(
                new ContextCheckAnalyzer.Judgement("NOTICE", Double.NaN, base.reason(), base.issue(),
                        base.sources(), base.relation(), 0, base.videoEvidence(), base.sourceEvidence(),
                        base.temporalStatus(), base.linkageReason(), base.reviewAction(), List.of()),
                new ContextCheckAnalyzer.Judgement("NOTICE", 0.9, base.reason(), base.issue(),
                        base.sources(), null, 0, base.videoEvidence(), base.sourceEvidence(),
                        base.temporalStatus(), base.linkageReason(), base.reviewAction(), List.of()))) {
            stub("롯데리아", article, result);
            assertThat(analyzer.analyze(context("롯데리아 회동 관련 수사"))).isEmpty();
        }
    }

    @Test
    void speechTopicJudgementDoesNotReceiveAdjacentOcrAsRepairContext() {
        stub("롯데리아", article, judgement("DIRECT_EVENT", "롯데리아 회동", "롯데리아 회동", List.of(0), List.of("회동")));
        var caption = new ScreenText(video, 0, 1_000, "다른출처에만있는문구", 0.9, null);
        caption.classify(ScreenTextRole.EDITORIAL, "편집 글자");
        var context = new AnalysisContext(video, null,
                List.of(new TranscriptSegment(video, 0, 1_000, "롯데리아 회동 관련 수사")), List.of(caption));
        assertThat(analyzer.analyze(context)).hasSize(1);
        var prompts = ArgumentCaptor.forClass(String.class);
        verify(client).completeAsJson(anyString(), prompts.capture(), eq(ContextCheckAnalyzer.Judgement.class));
        assertThat(prompts.getValue()).doesNotContain("다른출처에만있는문구");
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
