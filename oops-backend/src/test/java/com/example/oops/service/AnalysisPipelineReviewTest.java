package com.example.oops.service;

import com.example.oops.analyzer.*;
import com.example.oops.client.*;
import com.example.oops.config.*;
import com.example.oops.domain.*;
import com.example.oops.fusion.FindingFusionService;
import com.example.oops.genre.GenreDetector;
import com.example.oops.repository.*;
import com.example.oops.screentext.ScreenTextService;
import com.example.oops.transcript.TranscriptService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalysisPipelineReviewTest {
    @org.junit.jupiter.api.Test
    void snapshotReadFailureIsHandledBeforeAnyExternalAnalysis() {
        var progress=mock(JobProgressService.class); var videos=mock(VideoRepository.class);
        var ai=mock(OpenAiClient.class); var transcript=mock(TranscriptService.class);
        var screens=mock(ScreenTextService.class); var writer=mock(AnalysisResultWriter.class);
        when(progress.begin(11L)).thenReturn(1L);
        when(videos.findById(1L)).thenThrow(new IllegalStateException("fixture read failure"));
        var pipeline=new AnalysisPipeline(List.of(), new OopsProperties(null, null, null), transcript, screens,
                mock(FindingFusionService.class), mock(GenreDetector.class), mock(AnalysisServerClient.class),
                new ReportBuilder(), progress, videos, ai, writer);
        pipeline.run(11L);
        verify(progress).fail(eq(11L), eq("ANALYSIS_FAILED"), anyString());
        verifyNoInteractions(ai, transcript, screens, writer);
    }
    @ParameterizedTest
    @CsvSource({"SUCCESS,false", "PARTIAL,false", "PARTIAL,true", "FAILED,false"})
    void preservesWorkerOcrFailureStateEvenWithRetainedText(AnalyzerStatus state, boolean hasText) {
        var client = mock(OpenAiClient.class);
        var transcripts = mock(TranscriptService.class);
        var screens = mock(ScreenTextService.class);
        var progress = mock(JobProgressService.class);
        var videos = mock(VideoRepository.class);
        var writer = mock(AnalysisResultWriter.class);
        when(writer.save(eq(11L), any())).thenReturn(true);
        var worker = mock(AnalysisServerClient.class);
        var fusion = mock(FindingFusionService.class);
        var video = Video.builder().filename("offline.mp4").build();
        video.assignGenre(ContentGenre.GENERAL);
        when(progress.begin(11L)).thenReturn(1L);
        when(videos.findById(1L)).thenReturn(Optional.of(video));
        when(transcripts.extract(video)).thenReturn(List.of(new TranscriptSegment(video, 0, 1000, "정상 발언")));
        when(screens.extract(video, 11L)).thenReturn(hasText
                ? List.of(new ScreenText(video, 0, 1000, "화면 문구", 0.9, null)) : List.of());
        when(worker.lastOcrAssessment()).thenReturn(Optional.of(new AnalysisServerClient.OcrAssessment(state,
                state == AnalyzerStatus.SUCCESS ? null : "프레임 처리 실패 또는 일부 누락")));
        when(fusion.fuse(anyList())).thenReturn(List.of());
        var usage = mock(OpenAiClient.TokenUsage.class);
        when(usage.pricing()).thenReturn(mock(OpenAiProperties.Pricing.class));
        when(usage.isEmpty()).thenReturn(true);
        when(client.videoUsage()).thenReturn(usage);
        var pipeline = new AnalysisPipeline(List.of(), new OopsProperties(null,
                new OopsProperties.Analysis(List.of()), null), transcripts, screens, fusion,
                mock(GenreDetector.class), worker, new ReportBuilder(), progress, videos,
                client, writer);
        pipeline.run(11L);
        var captor = ArgumentCaptor.forClass(AnalysisResultWriter.Result.class);
        verify(writer).save(eq(11L), captor.capture());
        verify(screens).releaseFrames(video, 11L);
        var result = captor.getValue().coverage().stream().filter(c -> c.getStep() == CoverageStep.OCR).findFirst().orElseThrow();
        assertThat(result.getStatus()).isEqualTo(state);
        if (state != AnalyzerStatus.SUCCESS) assertThat(result.getMessage()).contains("프레임 처리");
        verify(progress, never()).fail(anyLong(), anyString(), anyString());
    }
    @org.junit.jupiter.api.Test
    void retainsIndependentWarningsAtTheSameSeverity() {
        var pipeline=mock(AnalysisPipeline.class, CALLS_REAL_METHODS);
        var video=Video.builder().filename("test.mp4").build();
        Map<CoverageStep, AnalysisCoverage> coverage=new EnumMap<>(CoverageStep.class);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(pipeline, "record", coverage, video,
                CoverageStep.SPEECH_REVIEW, AnalyzerStatus.PARTIAL, "발언 검증 일부 실패");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(pipeline, "record", coverage, video,
                CoverageStep.SPEECH_REVIEW, AnalyzerStatus.PARTIAL, "맥락 사전 후보 일부 보류");
        assertThat(coverage.get(CoverageStep.SPEECH_REVIEW).getMessage())
                .contains("발언 검증 일부 실패", "맥락 사전 후보 일부 보류");
        String longMessage=AnalysisCoverage.combineMessages("발언 검증 " + "가".repeat(290), "맥락 사전 보류 " + "나".repeat(290));
        assertThat(longMessage).hasSizeLessThanOrEqualTo(300).contains("발언 검증", "맥락 사전 보류");
    }
    @ParameterizedTest
    @CsvSource({"SUCCESS,false", "PARTIAL,false", "PARTIAL,true", "FAILED,true"})
    void propagatesValidatedCoverageAndDrainsIntermediateResults(AnalyzerStatus expected, boolean simulateApiFailure) {
        var client = mock(OpenAiClient.class);
        var analyzer = new SpeechReviewAnalyzer(client, false); // segment-scope coverage propagation
        var transcripts = mock(TranscriptService.class);
        var screens = mock(ScreenTextService.class);
        var fusion = mock(FindingFusionService.class);
        var progress = mock(JobProgressService.class);
        var videoRepo = mock(VideoRepository.class);
        var analysisServer = mock(AnalysisServerClient.class);
        var writer = mock(AnalysisResultWriter.class);
        when(writer.save(eq(11L), any())).thenReturn(true);
        var props = new OopsProperties(null, new OopsProperties.Analysis(List.of("speech-review")), null);
        var pipeline = new AnalysisPipeline(List.of(analyzer), props, transcripts, screens, fusion,
                mock(GenreDetector.class), analysisServer, new ReportBuilder(), progress, videoRepo, client, writer);
        var video = Video.builder().filename("offline.mp4").build();
        video.assignGenre(ContentGenre.GENERAL);
        var input = expected == AnalyzerStatus.PARTIAL && simulateApiFailure
                ? java.util.stream.IntStream.range(0, 25)
                .mapToObj(i -> new TranscriptSegment(video, i * 1_000, i * 1_000 + 500, "일반적인 상황 설명" + i)).toList()
                : List.of(new TranscriptSegment(video, 0, 1_000, "일반적인 상황 설명"));
        when(progress.begin(11L)).thenReturn(1L);
        when(videoRepo.findById(1L)).thenReturn(Optional.of(video));
        when(transcripts.extract(video)).thenReturn(input);
        when(screens.extract(video, 11L)).thenReturn(List.of());
        when(analysisServer.lastFailureDetail()).thenReturn(Optional.empty());
        when(fusion.fuse(anyList())).thenAnswer(i -> i.getArgument(0));
        when(client.isEnabled()).thenReturn(true);
        var usage = mock(OpenAiClient.TokenUsage.class);
        when(usage.pricing()).thenReturn(mock(OpenAiProperties.Pricing.class));
        when(usage.isEmpty()).thenReturn(true);
        when(client.videoUsage()).thenReturn(usage);
        String decision = expected == AnalyzerStatus.PARTIAL ? "UNCERTAIN" : "PASS";
        var item = new TextReviewEngine.LlmDecision("stt-index-0", decision, "상황 설명", "일반적인 상황 설명입니다.",
                null, null, null, null, null, expected == AnalyzerStatus.PARTIAL ? List.of("발언의 대상") : List.of());
        when(client.completeAsJson(anyString(), anyString(), eq(TextReviewEngine.LlmResult.class)))
                .thenReturn(expected == AnalyzerStatus.FAILED ? Optional.empty()
                        : Optional.of(new TextReviewEngine.LlmResult(List.of(item))));
        if (expected == AnalyzerStatus.PARTIAL && simulateApiFailure) {
            var firstBatch = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(i -> new TextReviewEngine.LlmDecision("stt-index-" + i, "PASS", "상황 설명",
                            "일반적인 상황 설명입니다.", null, null, null, null, null, List.<String>of())).toList();
            when(client.completeAsJson(anyString(), anyString(), eq(TextReviewEngine.LlmResult.class)))
                    .thenReturn(Optional.of(new TextReviewEngine.LlmResult(firstBatch)), Optional.empty());
        }
        if (simulateApiFailure) {
            when(client.failureCount()).thenReturn(1);
            when(client.failureReason()).thenReturn(Optional.of("모의 API 실패"));
        }
        pipeline.run(11L);
        var captor = ArgumentCaptor.forClass(AnalysisResultWriter.Result.class);
        verify(writer).save(eq(11L), captor.capture());
        var coverage = captor.getValue().coverage().stream().filter(c -> c.getStep() == CoverageStep.SPEECH_REVIEW).findFirst().orElseThrow();
        for (var optional : List.of(CoverageStep.FACT_ENTITY, CoverageStep.CONTEXT_REFERENCE)) {
            var disabled = captor.getValue().coverage().stream().filter(c -> c.getStep() == optional).findFirst().orElseThrow();
            assertThat(disabled.getStatus()).isEqualTo(AnalyzerStatus.NOT_ENABLED);
            assertThat(disabled.needsWarning()).isFalse();
        }
        assertThat(coverage.getStatus()).isEqualTo(expected);
        assertThat(coverage.needsWarning()).isEqualTo(expected != AnalyzerStatus.SUCCESS);
        if (expected != AnalyzerStatus.SUCCESS) assertThat(coverage.getMessage()).isNotBlank();
        assertThat(analyzer.consumeReviewResult(new AnalysisContext(video, null, input, null))).isEmpty();
        verify(progress, never()).fail(anyLong(), anyString(), anyString());
        assertThat(captor.getValue().diagnostics()).singleElement().extracting(ReviewDiagnostics::status).isEqualTo(expected);
    }
}
