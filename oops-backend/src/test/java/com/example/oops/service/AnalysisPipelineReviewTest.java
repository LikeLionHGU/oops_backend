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
    @SuppressWarnings({"unchecked", "rawtypes"})
    void propagatesValidatedCoverageAndDrainsIntermediateResults(AnalyzerStatus expected, boolean simulateApiFailure) {
        var client = mock(OpenAiClient.class);
        var analyzer = new SpeechReviewAnalyzer(client, false); // segment-scope coverage propagation
        var transcripts = mock(TranscriptService.class);
        var screens = mock(ScreenTextService.class);
        var fusion = mock(FindingFusionService.class);
        var progress = mock(JobProgressService.class);
        var videoRepo = mock(VideoRepository.class);
        var findingsRepo = mock(RiskFindingRepository.class);
        var coverageRepo = mock(AnalysisCoverageRepository.class);
        var analysisServer = mock(AnalysisServerClient.class);
        var reportRepo = mock(AnalysisReportRepository.class);
        var diagnosticsStore = mock(ReviewDiagnosticsStore.class);
        var props = new OopsProperties(null, new OopsProperties.Analysis(List.of("speech-review")), null);
        var pipeline = new AnalysisPipeline(List.of(analyzer), props, transcripts, screens, fusion,
                mock(GenreDetector.class), analysisServer, new ReportBuilder(), progress, videoRepo, findingsRepo,
                coverageRepo, mock(ReviewActionRepository.class), client, mock(ReviewReferenceRepository.class), reportRepo,
                diagnosticsStore, mock(ExpressionService.class));
        var video = Video.builder().filename("offline.mp4").build();
        video.assignGenre(ContentGenre.GENERAL);
        var input = expected == AnalyzerStatus.PARTIAL && simulateApiFailure
                ? java.util.stream.IntStream.range(0, 25)
                .mapToObj(i -> new TranscriptSegment(video, i * 1_000, i * 1_000 + 500, "일반적인 상황 설명" + i)).toList()
                : List.of(new TranscriptSegment(video, 0, 1_000, "일반적인 상황 설명"));
        when(progress.begin(11L)).thenReturn(1L);
        when(progress.complete(11L)).thenReturn(true);
        when(videoRepo.findById(1L)).thenReturn(Optional.of(video));
        when(transcripts.extractAndSave(video)).thenReturn(input);
        when(screens.extractAndSave(video)).thenReturn(List.of());
        when(analysisServer.lastFailureDetail()).thenReturn(Optional.empty());
        when(fusion.fuse(anyList())).thenAnswer(i -> i.getArgument(0));
        when(reportRepo.findByVideoId(1L)).thenReturn(Optional.empty());
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
        pipeline.runAsync(11L);
        ArgumentCaptor<Collection<AnalysisCoverage>> captor = ArgumentCaptor.forClass((Class) Collection.class);
        verify(coverageRepo).saveAll(captor.capture());
        var coverage = captor.getValue().stream().filter(c -> c.getStep() == CoverageStep.SPEECH_REVIEW).findFirst().orElseThrow();
        for (var optional : List.of(CoverageStep.FACT_ENTITY, CoverageStep.CONTEXT_REFERENCE)) {
            var disabled = captor.getValue().stream().filter(c -> c.getStep() == optional).findFirst().orElseThrow();
            assertThat(disabled.getStatus()).isEqualTo(AnalyzerStatus.NOT_ENABLED);
            assertThat(disabled.needsWarning()).isFalse();
        }
        assertThat(coverage.getStatus()).isEqualTo(expected);
        assertThat(coverage.needsWarning()).isEqualTo(expected != AnalyzerStatus.SUCCESS);
        if (expected != AnalyzerStatus.SUCCESS) assertThat(coverage.getMessage()).isNotBlank();
        assertThat(analyzer.consumeReviewResult(new AnalysisContext(video, null, input, null))).isEmpty();
        verify(progress, never()).fail(anyLong(), anyString(), anyString());
        verify(progress).complete(11L);
        verify(diagnosticsStore).recordAfterCommit(eq(1L), eq(11L), any(), argThat(traces ->
                traces.size() == 1 && traces.get(0).status() == expected));
    }
}
