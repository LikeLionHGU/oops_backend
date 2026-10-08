package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.oops.analyzer.TextReviewEngine.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewDiagnosticsTest {
    private final OpenAiClient client = mock(OpenAiClient.class);
    private final SpeechReviewAnalyzer analyzer = new SpeechReviewAnalyzer(client, false); // segment diagnostics
    private final Video video = Video.builder().filename("offline.mp4").build();
    private AnalysisContext context() {
        return new AnalysisContext(video, ContentGenre.GENERAL,
                List.of(new TranscriptSegment(video, 0, 1000, "음식이 맛있다")), null);
    }
    private LlmDecision decision(String kind, String quote) {
        return new LlmDecision("stt-index-0", kind, quote,
                "화자가 음식의 맛에 대한 개인적인 선호를 이야기하는 발언입니다.",
                null, null, null, null, null, List.of());
    }
    private ReviewDiagnostics run(AnalysisContext context) {
        analyzer.analyze(context);
        return analyzer.consumeReviewResult(context).orElseThrow().diagnostics();
    }
    @Test void capturesPassWithoutRetainingRawQuotes() {
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class)))
                .thenReturn(Optional.of(new LlmResult(List.of(decision("PASS", "맛있다")))));
        var trace = run(context()).segments().get(0);
        assertThat(trace.state()).isEqualTo(ReviewDiagnostics.State.PASS);
        assertThat(trace.decisions()).hasSize(1);
        assertThat(trace.decisions().get(0).evidence()).containsExactly(
                new ReviewDiagnostics.EvidenceLink("stt-index-0", ReviewEvaluation.EvidenceRole.PRIMARY));
    }
    @Test void rejectionIsRecoverableAndHistoryDoesNotChangeFinalState() {
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class)))
                .thenReturn(Optional.of(new LlmResult(List.of(decision("PASS", "없는 인용")))),
                        Optional.of(new LlmResult(List.of(decision("PASS", "맛있다")))));
        var diagnostics = run(context());
        assertThat(diagnostics.segments().get(0).state()).isEqualTo(ReviewDiagnostics.State.PASS);
        assertThat(diagnostics.failureCounts()).containsEntry(ReviewDiagnostics.Failure.QUOTE_NOT_IN_RAW, 1);
        assertThat(diagnostics.segments().get(0).rejections()).hasSize(1);
        assertThat(diagnostics.toString()).doesNotContain("없는 인용");
    }
    @Test void recordsRepeatedRejectionAndNotAnImplicitPass() {
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class)))
                .thenReturn(Optional.of(new LlmResult(List.of(decision("WRONG", "맛있다")))));
        var trace = run(context()).segments().get(0);
        assertThat(trace.state()).isEqualTo(ReviewDiagnostics.State.REJECTED);
        assertThat(trace.rejectedAttempts()).isEqualTo(2);
        assertThat(trace.rejections()).allSatisfy(r -> {
            assertThat(r.failure()).isEqualTo(ReviewDiagnostics.Failure.INVALID_DECISION);
            assertThat(r.returnedDecision()).isEqualTo("INVALID");
        });
    }
    @Test void emptyResponseIsCallFailed() {
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class))).thenReturn(Optional.empty());
        assertThat(run(context()).segments().get(0).state()).isEqualTo(ReviewDiagnostics.State.CALL_FAILED);
    }
    @Test void unknownAnchorCountsButDoesNotExposeArbitraryIdentifier() {
        var wrong = new LlmDecision("untrusted-secret", "PASS", "맛있다", "reason", null, null, null, null, null, List.of());
        when(client.completeAsJson(anyString(), anyString(), eq(LlmResult.class)))
                .thenReturn(Optional.of(new LlmResult(List.of(wrong))));
        var result = run(context());
        assertThat(result.segments().get(0).state()).isEqualTo(ReviewDiagnostics.State.NOT_RETURNED);
        assertThat(result.failureCounts()).containsEntry(ReviewDiagnostics.Failure.UNKNOWN_ANCHOR, 2);
        assertThat(result.toString()).doesNotContain("untrusted-secret");
    }
    @Test void finalStateUsesAllDecisionsEvenWhenStoredDetailsAreClipped() {
        var input = context().reviewInput();
        var collector = new ReviewDiagnostics.Collector(input, TimelineEventType.SPEECH);
        for (int i = 0; i < 5; i++) collector.accepted(new ReviewEvaluation.Observation(
                "stt-index-0", ReviewEvaluation.Decision.PASS, "설명".repeat(200), List.of(), List.of()));
        var result = collector.finish("test", AnalyzerStatus.PARTIAL, Set.of("stt-index-0"), Set.of(),
                Map.of("stt-index-0", EnumSet.of(ReviewEvaluation.Decision.UNCERTAIN)));
        assertThat(result.segments().get(0).state()).isEqualTo(ReviewDiagnostics.State.UNCERTAIN);
        assertThat(result.segments().get(0).decisions()).hasSize(4);
        assertThat(result.segments().get(0).decisions().get(0).reason()).hasSize(240);
        assertThat(result.truncated()).isTrue();
        assertThat(collector.finish("test", AnalyzerStatus.PARTIAL, Set.of(), Set.of("stt-index-0"), Map.of())
                .segments().get(0).state()).isEqualTo(ReviewDiagnostics.State.CONFLICT);
    }
    @Test void boundsSegmentAndRejectionDetailsButRetainsTotalFailureCount() {
        var segments = java.util.stream.IntStream.range(0, 401).mapToObj(i ->
                new ReviewInput.Segment("s" + i, TimelineEventType.SPEECH, i, i + 1, "text", null)).toList();
        var collector = new ReviewDiagnostics.Collector(new ReviewInput(segments), TimelineEventType.SPEECH);
        for (int i = 0; i < 6; i++) collector.reject("s0", "PASS", ReviewDiagnostics.Failure.MISSING_EVIDENCE, false);
        var result = collector.finish("test", AnalyzerStatus.FAILED, Set.of(), Set.of(), Map.of());
        assertThat(result.totalSegments()).isEqualTo(401);
        assertThat(result.segments()).hasSize(400);
        assertThat(result.segments().get(0).rejections()).hasSize(4);
        assertThat(result.failureCounts()).containsEntry(ReviewDiagnostics.Failure.MISSING_EVIDENCE, 6);
        assertThat(result.truncated()).isTrue();
    }
    @Test void backgroundOcrIsNotSelectedRatherThanPass() {
        var input = new ReviewInput(List.of(new ReviewInput.Segment("ocr-1", TimelineEventType.CAPTION,
                0, 1000, "간판", null, ScreenTextRole.BACKGROUND)));
        var collector = new ReviewDiagnostics.Collector(input, TimelineEventType.CAPTION);
        assertThat(collector.finish("test", AnalyzerStatus.PARTIAL, Set.of(), Set.of(), Map.of())
                .segments().get(0).state()).isEqualTo(ReviewDiagnostics.State.NOT_SELECTED);
    }
}
