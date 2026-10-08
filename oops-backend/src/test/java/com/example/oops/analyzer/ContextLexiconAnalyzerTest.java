package com.example.oops.analyzer;

import com.example.oops.domain.*;
import com.example.oops.lexicon.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class ContextLexiconAnalyzerTest {
    private final ContextLexicon lexicon = mock(ContextLexicon.class);
    private final ContextValidator validator = mock(ContextValidator.class);
    private final ContextLexiconAnalyzer analyzer = new ContextLexiconAnalyzer(lexicon, validator);
    private final Video video = Video.builder().filename("test.mp4").build();

    private void matchAnchor() {
        var entry = new ContextLexiconEntry("test", List.of("표현"), null, ContextTriggerMode.CONTEXT_REQUIRED,
                List.of(), List.of(), true, "문맥 확인", "2026-10-08");
        when(lexicon.match("표현")).thenReturn(List.of(new ContextLexicon.Match(entry, "표현", false, false)));
        when(validator.validate(anyList())).thenReturn(Map.of(0,
                new ContextValidator.Verdict(0, "LITERAL", null, null)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<ContextValidator.Request> requests() {
        ArgumentCaptor<List<ContextValidator.Request>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(validator).validate(captor.capture());
        return captor.getValue();
    }

    @Test
    void sendsMultipleSpeechNeighborsAndAllNearbyCaptionsWithoutExtraCalls() {
        matchAnchor();
        var context = new AnalysisContext(video, ContentGenre.GENERAL, List.of(
                new TranscriptSegment(video, 0, 1_000, "앞1"),
                new TranscriptSegment(video, 2_000, 3_000, "앞2"),
                new TranscriptSegment(video, 4_000, 5_000, "표현"),
                new TranscriptSegment(video, 6_000, 7_000, "뒤1"),
                new TranscriptSegment(video, 8_000, 9_000, "뒤2"),
                new TranscriptSegment(video, 60_000, 61_000, "먼 발언")), List.of(
                new ScreenText(video, 4_000, 5_000, "자막1", 0.9, null),
                new ScreenText(video, 4_500, 5_500, "자막2", 0.8, null),
                new ScreenText(video, 30_000, 31_000, "먼 자막", 0.9, null)));
        assertThat(analyzer.analyze(context)).isEmpty();
        var request = requests().get(0);
        assertThat(request.before()).isEqualTo("앞1\n앞2");
        assertThat(request.after()).isEqualTo("뒤1\n뒤2");
        assertThat(request.relatedText()).isEqualTo("[출처 불확실 화면 글자] 자막1\n[출처 불확실 화면 글자] 자막2");
        assertThat(request.line()).isEqualTo("표현");
        assertThat(analyzer.consumeCoverageNotice(context)).isEmpty();
    }

    @Test
    void keepsSttOnlyAndOcrOnlyRequestsWorking() {
        matchAnchor();
        var context = new AnalysisContext(video, null,
                List.of(new TranscriptSegment(video, 0, 1, "표현")), null);
        analyzer.analyze(context);
        var request = requests().get(0);
        assertThat(request.source()).isEqualTo("음성 STT 대본");
        assertThat(request.relatedText()).isNull();
        clearInvocations(validator);
        var caption = new ScreenText(video, 0, 1, "표현", 0.9, null);
        caption.classify(ScreenTextRole.EDITORIAL, "테스트용 편집 텍스트");
        analyzer.analyze(new AnalysisContext(video, null, null, List.of(caption)));
        assertThat(requests().get(0).source()).isEqualTo("화면 OCR 텍스트");
    }

    @Test
    void reportsContextOmissionAndClearsNoticeAfterConsumption() {
        matchAnchor();
        var transcript = new java.util.ArrayList<>(IntStream.range(0, 10)
                .mapToObj(i -> new TranscriptSegment(video, i * 500, i * 500 + 100, "문맥" + i)).toList());
        transcript.add(new TranscriptSegment(video, 6_000, 7_000, "표현"));
        var context = new AnalysisContext(video, null, transcript, null);
        analyzer.analyze(context);
        assertThat(requests().get(0).before().split("\n")).hasSize(8);
        assertThat(analyzer.consumeCoverageNotice(context)).hasValueSatisfying(s -> assertThat(s).contains("입력 한도"));
        assertThat(analyzer.consumeCoverageNotice(context)).isEmpty();
    }

    @Test
    void preservesFindingSourceRawTextAndAnchorTime() {
        matchAnchor();
        when(validator.validate(anyList())).thenReturn(Map.of(0,
                new ContextValidator.Verdict(0, "CONTEXTUAL", "대상", "구체적인 맥락")));
        var context = new AnalysisContext(video, null,
                List.of(new TranscriptSegment(video, 4_000, 5_000, "표현")), null);
        var findings = analyzer.analyze(context);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).getText()).isEqualTo("표현");
        assertThat(findings.get(0).getStartMs()).isEqualTo(4_000);
        assertThat(findings.get(0).getEndMs()).isEqualTo(5_000);
        assertThat(findings.get(0).getEventType()).isEqualTo(TimelineEventType.SPEECH);
    }
}
