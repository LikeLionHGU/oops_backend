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

/** Grounding and request plumbing only; not a real-model event relevance evaluation. */
class TopicExtractionContractTest {
    private final OpenAiClient ai = mock(OpenAiClient.class);
    private final ContextCheckAnalyzer analyzer = new ContextCheckAnalyzer(ai, List.of());
    private final Video video = Video.builder().filename("offline.mp4").build();
    private ContextCheckAnalyzer.Line line(TimelineEventType type, long time, String text) {
        return new ContextCheckAnalyzer.Line(type, time, time + 1000, text, null);
    }
    private ContextCheckAnalyzer.Topic topic(int i, String source, String keyword, String quote) {
        return new ContextCheckAnalyzer.Topic(i, keyword, "특정 장소의 화재 대응을 질문한다", source,
                List.of(new ContextCheckAnalyzer.TopicEvidence(i, quote)), List.of("중앙역", "화재"),
                "원문이 구체적인 화재 사건을 다룬다");
    }
    @SuppressWarnings("unchecked")
    private List<ContextCheckAnalyzer.Topic> extract(List<ContextCheckAnalyzer.Line> lines, int limit) throws Exception {
        var method = ContextCheckAnalyzer.class.getDeclaredMethod("extractTopics", List.class, int.class);
        method.setAccessible(true);
        return (List<ContextCheckAnalyzer.Topic>) method.invoke(analyzer, lines, limit);
    }
    @Test void groundedLongQueryIsAcceptedWithoutTwentyCharacterRestriction() {
        String raw = "중앙역 화재 당시 소방 대응과 대피 안내를 이야기하자";
        assertThat(ContextCheckAnalyzer.validTopic(topic(0, "SPEECH", raw, raw),
                List.of(line(TimelineEventType.SPEECH, 0, raw)))).isTrue();
    }
    @Test void fabricatedQuotesIndicesSourcesAndEventTermsAreRejected() {
        String raw = "중앙역 화재 당시 대응";
        var lines = List.of(line(TimelineEventType.SPEECH, 0, raw));
        assertThat(ContextCheckAnalyzer.validTopic(topic(0, "SPEECH", "중앙역 화재", "허구 인용"), lines)).isFalse();
        assertThat(ContextCheckAnalyzer.validTopic(topic(7, "SPEECH", "중앙역 화재", raw), lines)).isFalse();
        assertThat(ContextCheckAnalyzer.validTopic(topic(0, "CAPTION", "중앙역 화재", raw), lines)).isFalse();
        assertThat(ContextCheckAnalyzer.validTopic(topic(0, "SPEECH", "중앙역 화재 횡령 수사", raw), lines)).isFalse();
        var t = topic(0, "SPEECH", "중앙역 화재", raw);
        assertThat(ContextCheckAnalyzer.validTopic(new ContextCheckAnalyzer.Topic(0, t.keyword(), t.context(),
                t.source(), t.evidence(), List.of("원문에 없는 수사"), t.selectionReason()), lines)).isFalse();
    }
    @Test void crossSourceAndDistantEvidenceCannotRepairAName() {
        var lines = List.of(line(TimelineEventType.SPEECH, 0, "중앙역"),
                line(TimelineEventType.CAPTION, 0, "화재"),
                line(TimelineEventType.SPEECH, 30000, "화재"));
        for (int index : List.of(1, 2)) {
            var t = new ContextCheckAnalyzer.Topic(0, "중앙역 화재", "맥락", "SPEECH", List.of(
                    new ContextCheckAnalyzer.TopicEvidence(0, "중앙역"),
                    new ContextCheckAnalyzer.TopicEvidence(index, "화재")), List.of("중앙역", "화재"), "선택 이유");
            assertThat(ContextCheckAnalyzer.validTopic(t, lines)).isFalse();
        }
    }
    @Test void genericQueriesAndMissingAnchorAreRejected() {
        var lines = List.of(line(TimelineEventType.SPEECH, 0, "중앙역 화재"),
                line(TimelineEventType.SPEECH, 1000, "중앙역 화재 대응"));
        assertThat(ContextCheckAnalyzer.validTopic(topic(0, "SPEECH", "사회", "중앙역 화재"), lines)).isFalse();
        assertThat(ContextCheckAnalyzer.validTopic(new ContextCheckAnalyzer.Topic(0, "중앙역 화재", "맥락",
                "SPEECH", List.of(new ContextCheckAnalyzer.TopicEvidence(1, "중앙역 화재")),
                List.of("중앙역", "화재"), "선택 이유"), lines)).isFalse();
    }
    @Test void jsonInputCarriesSourceTimeRevisionAndRequestedLimit() throws Exception {
        String raw = "중앙역 화재 당시 대응";
        var lines = List.of(line(TimelineEventType.SPEECH, 5000, raw));
        when(ai.completeAsJson(anyString(), anyString(), eq(ContextCheckAnalyzer.TopicResult.class)))
                .thenReturn(Optional.of(new ContextCheckAnalyzer.TopicResult(List.of(topic(0, "SPEECH", "중앙역 화재", raw)))));
        assertThat(extract(lines, 8)).hasSize(1);
        var user = ArgumentCaptor.forClass(String.class);
        verify(ai).completeAsJson(anyString(), user.capture(), eq(ContextCheckAnalyzer.TopicResult.class));
        var input = tools.jackson.databind.json.JsonMapper.builder().build().readTree(user.getValue());
        assertThat(input.get("maxTopics").asInt()).isEqualTo(8);
        assertThat(input.get("recordedAtKnown").asBoolean()).isFalse();
        assertThat(input.get("promptRevision").asText()).isEqualTo(TextReviewEngine.PROMPT_REVISION);
        assertThat(input.get("lines").get(0).get("startMs").asLong()).isEqualTo(5000);
        assertThat(input.get("lines").get(0).get("source").asText()).isEqualTo("SPEECH");
    }
    @Test void validatesBeforeDeduplicationAndLimitAndReportsOmissions() throws Exception {
        var lines = new ArrayList<ContextCheckAnalyzer.Line>();
        var topics = new ArrayList<ContextCheckAnalyzer.Topic>();
        topics.add(null);
        for (int i = 0; i < 9; i++) {
            String raw = "중앙역 " + i + " 화재";
            lines.add(line(TimelineEventType.SPEECH, i * 1000, raw));
            topics.add(topic(i, "SPEECH", raw, raw));
        }
        topics.add(topics.get(1));
        when(ai.completeAsJson(anyString(), anyString(), eq(ContextCheckAnalyzer.TopicResult.class)))
                .thenReturn(Optional.of(new ContextCheckAnalyzer.TopicResult(topics)));
        assertThat(extract(lines, 5)).hasSize(5);
        assertThat(analyzer.consumeCoverageNotice(null)).hasValueSatisfying(s ->
                assertThat(s).contains("1건은 원문/형식 검증 실패", "4건은 검색 한도"));
        assertThat(analyzer.consumeCoverageNotice(null)).isEmpty();
    }
    @Test void emptyTopicsAreNotAnExtractionFailure() throws Exception {
        when(ai.completeAsJson(anyString(), anyString(), eq(ContextCheckAnalyzer.TopicResult.class)))
                .thenReturn(Optional.of(new ContextCheckAnalyzer.TopicResult(List.of())));
        assertThat(extract(List.of(), 5)).isEmpty();
        assertThat(analyzer.consumeCoverageNotice(null)).isEmpty();
    }
    @Test void failedExtractionIsCoverageNoticeNotAnEmptySuccess() throws Exception {
        when(ai.completeAsJson(anyString(), anyString(), eq(ContextCheckAnalyzer.TopicResult.class)))
                .thenReturn(Optional.empty());
        assertThat(extract(List.of(), 5)).isEmpty();
        assertThat(analyzer.consumeCoverageNotice(null)).isPresent();
    }
    @Test void collectedLinesAreTimeOrderedAndBackgroundOcrIsExcluded() throws Exception {
        var caption = new ScreenText(video, 1000, 2000, "중앙역 화재", .9, null);
        caption.classify(ScreenTextRole.EDITORIAL, "편집 글자");
        var background = new ScreenText(video, 0, 1000, "간판", .9, null);
        var context = new AnalysisContext(video, null,
                List.of(new TranscriptSegment(video, 3000, 4000, "대응 질문")), List.of(caption, background));
        var method = ContextCheckAnalyzer.class.getDeclaredMethod("collectLines", AnalysisContext.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked") var lines = (List<ContextCheckAnalyzer.Line>) method.invoke(analyzer, context);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).type()).isEqualTo(TimelineEventType.CAPTION);
        assertThat(lines.get(1).startMs()).isEqualTo(3000);
    }
}
