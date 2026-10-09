package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import com.example.oops.news.NewsSearchClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Mocked contracts, not proof of current status or semantic event identity. */
class ContextJudgementContractTest {
    private final Video video = Video.builder().filename("offline.mp4").build();
    private final String raw = "중앙역 화재 때 대피 안내가 어땠나요?";
    private final List<ContextCheckAnalyzer.Line> lines = List.of(new ContextCheckAnalyzer.Line(
            TimelineEventType.SPEECH, 0, 1000, raw, null));
    private final List<NewsSearchClient.NewsItem> news = List.of(
            new NewsSearchClient.NewsItem("역사 화재 대응 조사 재개", "중앙역 대피 안내 조사를 재개했다", "2026-10-09", "https://example.com/1"),
            new NewsSearchClient.NewsItem("중앙역 당시 대피 안내", "과거 대응을 회고했다", "2026-10-09", "https://example.com/2"));
    private ContextCheckAnalyzer.Judgement result(String decision, String relation, String temporal) {
        boolean notice = "NOTICE".equals(decision);
        return new ContextCheckAnalyzer.Judgement(decision, notice ? .8 : null,
                "자료는 해당 역사의 대피 안내 조사가 재개됐다고 보도합니다.", notice ? "대피 안내 조사" : null,
                List.of(0), relation, 0, raw,
                List.of(new ContextCheckAnalyzer.SourceQuote(0, "중앙역 대피 안내 조사를 재개했다")),
                temporal, "영상의 화재 당시 안내와 기사의 해당 역사 조사 경과를 대조합니다.",
                notice ? "당시 대피 안내 설명에 최근 재개된 조사의 내용이 반영되어야 하는지 확인하세요." : null,
                "UNCERTAIN".equals(decision) ? List.of("사건 발생 날짜와 현재 진행 상태") : List.of());
    }
    private boolean valid(ContextCheckAnalyzer.Judgement r) { return ContextCheckAnalyzer.validJudgement(r, lines, news); }
    @Test void groundedConnectionDoesNotRequireIdenticalActionWords() {
        assertThat(valid(result("NOTICE", "CONTEXTUAL_EVENT", "CURRENT_DEVELOPMENT"))).isTrue();
    }
    @Test void recentRetrospectiveDoesNotMeetNoticeTemporalContract() {
        assertThat(valid(result("NOTICE", "DIRECT_EVENT", "HISTORICAL_REPORT"))).isFalse();
        assertThat(valid(result("NO_NOTICE", "DIRECT_EVENT", "HISTORICAL_REPORT"))).isTrue();
    }
    @Test void unknownConnectedStatusRequiresUncertainty() {
        assertThat(valid(result("NOTICE", "DIRECT_EVENT", "UNKNOWN"))).isFalse();
        assertThat(valid(result("NO_NOTICE", "DIRECT_EVENT", "UNKNOWN"))).isFalse();
        assertThat(valid(result("UNCERTAIN", "DIRECT_EVENT", "UNKNOWN"))).isTrue();
    }
    @Test void mereMentionIsNoNoticeAndIncompleteConnectionIsUncertain() {
        assertThat(valid(result("NOTICE", "MERE_MENTION", "CURRENT_DEVELOPMENT"))).isFalse();
        assertThat(valid(result("NO_NOTICE", "MERE_MENTION", "UNKNOWN"))).isTrue();
        assertThat(valid(result("NO_NOTICE", "INSUFFICIENT_CONTEXT", "UNKNOWN"))).isFalse();
        assertThat(valid(result("UNCERTAIN", "INSUFFICIENT_CONTEXT", "UNKNOWN"))).isTrue();
    }
    @Test void everyReferencedArticleNeedsItsOwnExactQuote() {
        var r = result("NOTICE", "DIRECT_EVENT", "CURRENT_DEVELOPMENT");
        assertThat(valid(new ContextCheckAnalyzer.Judgement(r.decision(), r.score(), r.reason(), r.issue(),
                List.of(0, 1), r.relation(), r.videoIndex(), r.videoEvidence(), r.sourceEvidence(),
                r.temporalStatus(), r.linkageReason(), r.reviewAction(), r.missingInformation()))).isFalse();
        assertThat(valid(new ContextCheckAnalyzer.Judgement(r.decision(), r.score(), r.reason(), r.issue(),
                List.of(0), r.relation(), 0, raw,
                List.of(new ContextCheckAnalyzer.SourceQuote(0, "자료에 없는 현재 상태")),
                r.temporalStatus(), r.linkageReason(), r.reviewAction(), List.of()))).isFalse();
    }
    @Test void malformedNoticeIsRejectedAndRecordedAsCoverageFailure() {
        var r = result("NOTICE", "DIRECT_EVENT", "CURRENT_DEVELOPMENT");
        for (var invalid : List.of(
                new ContextCheckAnalyzer.Judgement(r.decision(), null, r.reason(), r.issue(), r.sources(),
                        r.relation(), 0, raw, r.sourceEvidence(), r.temporalStatus(), r.linkageReason(), r.reviewAction(), List.of()),
                new ContextCheckAnalyzer.Judgement(r.decision(), r.score(), r.reason(), r.issue(), List.of(0, 0),
                        r.relation(), 0, raw, r.sourceEvidence(), r.temporalStatus(), r.linkageReason(), r.reviewAction(), List.of()),
                new ContextCheckAnalyzer.Judgement(r.decision(), r.score(), r.reason(), r.issue(), r.sources(),
                        r.relation(), 7, raw, r.sourceEvidence(), r.temporalStatus(), r.linkageReason(), r.reviewAction(), List.of()),
                new ContextCheckAnalyzer.Judgement(r.decision(), r.score(), r.reason(), r.issue(), r.sources(),
                        r.relation(), 0, raw, r.sourceEvidence(), r.temporalStatus(), r.linkageReason(), "확인하세요", List.of()))) {
            assertThat(valid(invalid)).isFalse();
            var analyzer = analyzer(invalid, false, false);
            assertThat(analyzer.analyze(context())).isEmpty();
            assertThat(analyzer.consumeCoverageNotice(null)).isPresent();
        }
    }

    private ContextCheckAnalyzer analyzer(ContextCheckAnalyzer.Judgement result, boolean empty, boolean failure) {
        var ai = mock(OpenAiClient.class);
        var search = mock(NewsSearchClient.class);
        when(search.isEnabled()).thenReturn(true);
        if (failure) when(search.searchRecent(anyString(), anyInt())).thenThrow(new IllegalStateException("offline"));
        else when(search.searchRecent(anyString(), anyInt())).thenReturn(empty ? List.of() : news);
        when(ai.completeAsJson(anyString(), anyString(), eq(ContextCheckAnalyzer.TopicResult.class))).thenReturn(
                Optional.of(new ContextCheckAnalyzer.TopicResult(List.of(new ContextCheckAnalyzer.Topic(0,
                        "중앙역 화재", "당시 안내를 질문한다", "SPEECH",
                        List.of(new ContextCheckAnalyzer.TopicEvidence(0, raw)), List.of("중앙역", "화재"), "구체적인 사건 확인")))));
        when(ai.completeAsJson(anyString(), anyString(), eq(ContextCheckAnalyzer.Judgement.class)))
                .thenReturn(Optional.ofNullable(result));
        return new ContextCheckAnalyzer(ai, List.of(search));
    }
    private AnalysisContext context() {
        return new AnalysisContext(video, null, List.of(new TranscriptSegment(video, 0, 1000, raw)), List.of());
    }
    @Test void missingSearchFailedSearchFailedJudgementAndUncertaintyAreCoverageNotCards() {
        for (var analyzer : List.of(analyzer(null, true, false), analyzer(null, false, true),
                analyzer(null, false, false), analyzer(result("UNCERTAIN", "DIRECT_EVENT", "UNKNOWN"), false, false))) {
            assertThat(analyzer.analyze(context())).isEmpty();
            assertThat(analyzer.consumeCoverageNotice(null)).isPresent();
            assertThat(analyzer.consumeCoverageNotice(null)).isEmpty();
        }
    }
    @Test void noticePublishesConcreteReviewActionButNoNoticeDoesNotBecomeCoverageFailure() {
        var analyzer = analyzer(result("NOTICE", "DIRECT_EVENT", "CURRENT_DEVELOPMENT"), false, false);
        var findings = analyzer.analyze(context());
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).getReason()).contains("재확인 사항");
        assertThat(findings.get(0).getReferences()).hasSize(1);
        assertThat(findings.get(0).getScore()).isEqualTo(.69);
        analyzer = analyzer(result("NO_NOTICE", "DIRECT_EVENT", "HISTORICAL_REPORT"), false, false);
        assertThat(analyzer.analyze(context())).isEmpty();
        assertThat(analyzer.consumeCoverageNotice(null)).isEmpty();
    }
    @Test void inputIsJsonAndIncludesPublicationDatesNotStoragePaths() throws Exception {
        var ai = mock(OpenAiClient.class);
        when(ai.completeAsJson(anyString(), anyString(), eq(ContextCheckAnalyzer.Judgement.class))).thenReturn(Optional.empty());
        var analyzer = new ContextCheckAnalyzer(ai, List.of());
        var method = ContextCheckAnalyzer.class.getDeclaredMethod("judge", String.class, ContentGenre.class,
                ContextCheckAnalyzer.Topic.class, List.class, List.class);
        method.setAccessible(true);
        var topic = new ContextCheckAnalyzer.Topic(0, "중앙역 화재", "모델 추정", "SPEECH",
                List.of(new ContextCheckAnalyzer.TopicEvidence(0, raw)), List.of("화재"), "모델 선택 이유");
        method.invoke(analyzer, "2026-10-09", ContentGenre.GENERAL, topic, news, lines);
        var user = ArgumentCaptor.forClass(String.class);
        verify(ai).completeAsJson(anyString(), user.capture(), eq(ContextCheckAnalyzer.Judgement.class));
        var input = tools.jackson.databind.json.JsonMapper.builder().build().readTree(user.getValue());
        assertThat(input.get("recordedAtKnown").asBoolean()).isFalse();
        assertThat(input.get("articles").get(0).get("publishedAt").asText()).isEqualTo("2026-10-09");
        assertThat(input.get("videoLines").get(0).get("index").asInt()).isZero();
        assertThat(input.get("promptRevision").asText()).isEqualTo(TextReviewEngine.PROMPT_REVISION);
        assertThat(user.getValue()).doesNotContain("storageKey", "frame");
    }
}
