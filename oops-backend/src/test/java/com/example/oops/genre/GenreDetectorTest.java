package com.example.oops.genre;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Sampling/grounding contracts only, not real-model format recognition. */
class GenreDetectorTest {
    private final OpenAiClient ai = mock(OpenAiClient.class);
    private final GenreDetector detector = new GenreDetector(ai);
    private final Video video = Video.builder().filename("offline.mp4").build();
    private List<TranscriptSegment> transcript() {
        return List.of(new TranscriptSegment(video, 0, 1000, "오늘 작업은 어땠나요?"),
                new TranscriptSegment(video, 1000, 2000, "저는 준비하는 데 시간이 많이 걸렸어요."));
    }
    private GenreDetector.Result classified(String genre, double confidence) {
        return new GenreDetector.Result("CLASSIFIED", genre, confidence, "질문과 상대방의 경험 답변이 연결됩니다.",
                List.of(new GenreDetector.Evidence(0, "오늘 작업은 어땠나요?"),
                        new GenreDetector.Evidence(1, "저는 준비하는 데 시간이 많이 걸렸어요.")), List.of());
    }
    private void stub(GenreDetector.Result result) {
        when(ai.isEnabled()).thenReturn(true);
        when(ai.completeAsJson(anyString(), anyString(), eq(GenreDetector.Result.class))).thenReturn(Optional.ofNullable(result));
    }
    @Test void groundedConversationalFormatIsAdopted() {
        stub(classified("TALK_PODCAST", .8));
        var result = detector.detectDetailed(transcript(), List.of());
        assertThat(result.genre()).isEqualTo(ContentGenre.TALK_PODCAST);
        assertThat(result.status()).isEqualTo("ADOPTED");
    }
    @Test void groundedGeneralIsDifferentFromFallbackGeneral() {
        stub(classified("GENERAL", .8));
        assertThat(detector.detectDetailed(transcript(), List.of()).status()).isEqualTo("ADOPTED");
        stub(new GenreDetector.Result("UNCERTAIN", null, null, "본편 진행이 불명확합니다.", List.of(), List.of("본편 문맥")));
        var result = detector.detectDetailed(transcript(), List.of());
        assertThat(result.status()).isEqualTo("UNCERTAIN");
        assertThat(result.genre()).isEqualTo(ContentGenre.GENERAL);
    }
    @Test void lowConfidenceDoesNotSelectExpandedTalkBudget() {
        stub(classified("TALK_PODCAST", .69));
        assertThat(detector.detectDetailed(transcript(), List.of()).status()).isEqualTo("LOW_CONFIDENCE");
        assertThat(detector.detect(transcript(), List.of())).isEqualTo(ContentGenre.GENERAL);
    }
    @Test void invalidEnumsNonfiniteConfidenceAndInventedQuotesAreRejected() {
        for (var result : List.of(classified("OTHER", .9), classified("TALK_PODCAST", Double.NaN),
                classified("TALK_PODCAST", 1.1),
                new GenreDetector.Result("CLASSIFIED", "GENERAL", .8, "원문 근거 판단", List.of(
                        new GenreDetector.Evidence(0, "원문에 없는 문장")), List.of()))) {
            stub(result);
            assertThat(detector.detectDetailed(transcript(), List.of()).status()).isEqualTo("INVALID_RESPONSE");
        }
    }
    @Test void missingAndDuplicateEvidenceDoNotProveDialogue() {
        var sample = GenreDetector.sampleLines(transcript(), List.of());
        assertThat(GenreDetector.validResult(new GenreDetector.Result("CLASSIFIED", "TALK_PODCAST", .9,
                "질문답변 판단", List.of(new GenreDetector.Evidence(0, "어땠나요?")), List.of()), sample)).isFalse();
        assertThat(GenreDetector.validResult(new GenreDetector.Result("CLASSIFIED", "TALK_PODCAST", .9,
                "질문답변 판단", List.of(new GenreDetector.Evidence(0, "어땠나요?"),
                        new GenreDetector.Evidence(0, "어땠나요?")), List.of()), sample)).isFalse();
    }
    @Test void separatedWindowsCannotBeSplicedIntoDialogueEvidence() {
        var sample = List.of(new GenreDetector.SampleLine(0, "SPEECH", 0, 1000, "질문", 0, false),
                new GenreDetector.SampleLine(1, "SPEECH", 90000, 91000, "답변", 2, false));
        assertThat(GenreDetector.validResult(new GenreDetector.Result("CLASSIFIED", "TALK_PODCAST", .8,
                "연결된 질문답변", List.of(new GenreDetector.Evidence(0, "질문"),
                        new GenreDetector.Evidence(1, "답변")), List.of()), sample)).isFalse();
    }
    @Test void ocrOnlyEvidenceCannotProveMultipleSpeakers() {
        var sample = List.of(new GenreDetector.SampleLine(0, "CAPTION", 0, 1000, "질문", 3, false),
                new GenreDetector.SampleLine(1, "CAPTION", 1000, 2000, "답변", 3, false));
        assertThat(GenreDetector.validResult(new GenreDetector.Result("CLASSIFIED", "TALK_PODCAST", .8,
                "연결된 질문답변", List.of(new GenreDetector.Evidence(0, "질문"),
                        new GenreDetector.Evidence(1, "답변")), List.of()), sample)).isFalse();
    }
    @Test void unavailableAiDoesNotClassifyUsingSingleInterviewKeyword() {
        var result = detector.detectDetailed(List.of(new TranscriptSegment(video, 0, 1000, "인터뷰 광고입니다")), List.of());
        assertThat(result.genre()).isEqualTo(ContentGenre.GENERAL);
        assertThat(result.status()).isEqualTo("API_UNAVAILABLE");
        verify(ai, never()).completeAsJson(anyString(), anyString(), any());
    }
    @Test void emptyInputFailedResponseAndThrownRequestAreDifferentStatuses() {
        assertThat(detector.detectDetailed(List.of(), List.of()).status()).isEqualTo("NO_INPUT");
        stub(null);
        assertThat(detector.detectDetailed(transcript(), List.of()).status()).isEqualTo("INVALID_RESPONSE");
        when(ai.completeAsJson(anyString(), anyString(), eq(GenreDetector.Result.class))).thenThrow(new IllegalStateException("offline"));
        assertThat(detector.detectDetailed(transcript(), List.of()).status()).isEqualTo("API_FAILED");
    }
    @Test void spreadSampleIsBoundedAndContainsStartMiddleEnd() {
        var transcript = java.util.stream.IntStream.range(0, 100).mapToObj(i ->
                new TranscriptSegment(video, i * 1000L, i * 1000L + 500, "내용" + i)).toList();
        var sample = GenreDetector.sampleLines(transcript, List.of());
        assertThat(sample).hasSize(40);
        assertThat(sample.stream().map(GenreDetector.SampleLine::text)).contains("내용0", "내용50", "내용99");
        assertThat(sample.stream().map(GenreDetector.SampleLine::window).distinct()).containsExactly(0, 1, 2);
    }
    @Test void onlyEditorialOcrIsSampledAndLongUnicodeIsSafelyTruncated() {
        var caption = new ScreenText(video, 0, 1000, "😀".repeat(500), .9, null);
        caption.classify(ScreenTextRole.EDITORIAL, "편집 글자");
        var background = new ScreenText(video, 0, 1000, "간판", .9, null);
        var sample = GenreDetector.sampleLines(List.of(), List.of(caption, background));
        assertThat(sample).hasSize(1);
        assertThat(sample.get(0).text().codePointCount(0, sample.get(0).text().length())).isEqualTo(150);
        assertThat(sample.get(0).truncated()).isTrue();
    }
    @Test void jsonIncludesProvenanceAndNotFramePaths() {
        stub(classified("GENERAL", .8));
        detector.detect(transcript(), List.of());
        var user = ArgumentCaptor.forClass(String.class);
        verify(ai).completeAsJson(anyString(), user.capture(), eq(GenreDetector.Result.class));
        var input = tools.jackson.databind.json.JsonMapper.builder().build().readTree(user.getValue());
        assertThat(input.get("lines").get(0).get("source").asText()).isEqualTo("SPEECH");
        assertThat(input.get("lines").get(0).get("startMs").asLong()).isZero();
        assertThat(input.get("promptRevision").asText()).isEqualTo(com.example.oops.analyzer.TextReviewEngine.PROMPT_REVISION);
        assertThat(user.getValue()).doesNotContain("frame", "storageKey");
    }
}
