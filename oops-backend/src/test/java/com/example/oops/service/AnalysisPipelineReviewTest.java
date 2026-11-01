package com.example.oops.service;

import com.example.oops.analyzer.*;
import com.example.oops.client.*;
import com.example.oops.config.*;
import com.example.oops.domain.*;
import com.example.oops.fusion.FindingFusionService;
import com.example.oops.repository.*;
import com.example.oops.transcript.TranscriptService;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalysisPipelineReviewTest {
    @org.junit.jupiter.api.Test
    void successfulFindingResultWithCoverageGapCannotHideTheWarning() {
        assertThat(AnalysisPipeline.coverageStatus(AnalyzerStatus.SUCCESS, "사례집을 불러오지 못했습니다."))
                .isEqualTo(AnalyzerStatus.PARTIAL);
        assertThat(AnalysisPipeline.coverageStatus(AnalyzerStatus.SUCCESS, null)).isEqualTo(AnalyzerStatus.SUCCESS);
        assertThat(AnalysisPipeline.coverageStatus(AnalyzerStatus.FAILED, "사례집을 불러오지 못했습니다."))
                .isEqualTo(AnalyzerStatus.FAILED);
    }
    @org.junit.jupiter.api.Test
    void snapshotReadFailureIsHandledBeforeAnyExternalAnalysis() {
        var progress=mock(JobProgressService.class); var videos=mock(VideoRepository.class);
        var ai=mock(OpenAiClient.class); var transcript=mock(TranscriptService.class);
        var writer=mock(AnalysisResultWriter.class);
        when(progress.begin(11L)).thenReturn(1L);
        when(videos.findById(1L)).thenThrow(new IllegalStateException("fixture read failure"));
        var pipeline=new AnalysisPipeline(List.of(), new OopsProperties(null, null, null), transcript,
                mock(FindingFusionService.class), mock(AnalysisServerClient.class),
                new ReportBuilder(), progress, videos, ai, writer);
        pipeline.run(11L);
        verify(progress).fail(eq(11L), eq("ANALYSIS_FAILED"), anyString());
        verifyNoInteractions(ai, transcript, writer);
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
    @org.junit.jupiter.api.Test
    void aggregationRetainsContextCaveatWithoutDowngradingSuccessfulProcessing() {
        var pipeline = mock(AnalysisPipeline.class, CALLS_REAL_METHODS);
        var video = Video.builder().filename("test.mp4").build();
        Map<CoverageStep, AnalysisCoverage> coverage = new EnumMap<>(CoverageStep.class);
        var first = AnalysisCoverage.of(video, CoverageStep.SPEECH_REVIEW, AnalyzerStatus.SUCCESS, null);
        first.markContextLimited("제한된 문맥 창");
        coverage.put(CoverageStep.SPEECH_REVIEW, first);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(pipeline, "record", coverage, video,
                CoverageStep.SPEECH_REVIEW, AnalyzerStatus.SUCCESS, null);
        var merged = coverage.get(CoverageStep.SPEECH_REVIEW);
        assertThat(merged.getStatus()).isEqualTo(AnalyzerStatus.SUCCESS);
        assertThat(merged.warningCode()).isEqualTo("SPEECH_REVIEW_CONTEXT_LIMITED");
        assertThat(merged.needsWarning()).isTrue();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(pipeline, "record", coverage, video,
                CoverageStep.SPEECH_REVIEW, AnalyzerStatus.PARTIAL, "실제 응답 누락");
        assertThat(coverage.get(CoverageStep.SPEECH_REVIEW).warningCode()).isEqualTo("SPEECH_REVIEW_PARTIAL");
        assertThat(coverage.get(CoverageStep.SPEECH_REVIEW).getMessage()).contains("문맥 창", "응답 누락");
    }
    @org.junit.jupiter.api.Test
    void analyzersRunInParallelAndFailuresAreTrackedPerAnalyzer() {
        var client = new OpenAiClient(org.springframework.web.client.RestClient.create(),
                new OpenAiProperties("offline-test-key", null, null, "https://example.invalid", null, null,
                        java.time.Duration.ofSeconds(2), null));
        var transcripts = mock(TranscriptService.class);
        var progress = mock(JobProgressService.class);
        var videos = mock(VideoRepository.class);
        var writer = mock(AnalysisResultWriter.class);
        when(writer.save(eq(11L), any())).thenReturn(true);
        var fusion = mock(FindingFusionService.class);
        when(fusion.fuse(anyList())).thenAnswer(call -> call.getArgument(0));
        var video = Video.builder().filename("offline.mp4").build();
        when(progress.begin(11L)).thenReturn(1L);
        when(videos.findById(1L)).thenReturn(Optional.of(video));
        when(transcripts.extract(video)).thenReturn(List.of(new TranscriptSegment(video, 0, 1000, "정상 발언")));
        var bothStarted = new java.util.concurrent.CountDownLatch(2);
        ContentAnalyzer context = analyzer("context-review", () -> {
            bothStarted.countDown();
            await(bothStarted);
            return List.of(RiskFinding.builder().video(video).category(RiskCategory.CONTEXT_CRITERION)
                    .source(EvidenceSource.SUBTITLE).score(.5).startMs(0).endMs(1000).text("정상 발언").reason("r").build());
        });
        ContentAnalyzer fact = analyzer("entity-check", () -> {
            bothStarted.countDown();
            await(bothStarted);   // 두 분석기가 동시에 돌지 않으면 여기서 멈춘다
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(client, "fail",
                    failureCode("HTTP_FAILED"), "검색 판정 호출 실패");
            return List.of();
        });
        var pipeline = new AnalysisPipeline(List.of(context, fact), new OopsProperties(null,
                new OopsProperties.Analysis(List.of("context-review", "entity-check")), null), transcripts, fusion,
                mock(AnalysisServerClient.class), new ReportBuilder(), progress, videos, client, writer);
        pipeline.run(11L);
        var captor = ArgumentCaptor.forClass(AnalysisResultWriter.Result.class);
        verify(writer).save(eq(11L), captor.capture());
        var coverage = captor.getValue().coverage();
        assertThat(coverage).filteredOn(c -> c.getStep() == CoverageStep.SPEECH_REVIEW).singleElement()
                .extracting(AnalysisCoverage::getStatus).isEqualTo(AnalyzerStatus.SUCCESS);
        assertThat(coverage).filteredOn(c -> c.getStep() == CoverageStep.FACT_ENTITY).singleElement()
                .satisfies(c -> {
                    assertThat(c.getStatus()).isEqualTo(AnalyzerStatus.FAILED);
                    assertThat(c.getMessage()).contains("검색 판정 호출 실패");
                });
        assertThat(captor.getValue().findings()).hasSize(1);
        verify(progress, never()).fail(anyLong(), anyString(), anyString());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object failureCode(String name) {
        try {
            Class type = Class.forName("com.example.oops.client.OpenAiClient$FailureCode");
            return Enum.valueOf(type, name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("analyzers did not overlap");
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    static ContentAnalyzer analyzer(String key, java.util.function.Supplier<List<RiskFinding>> body) {
        return new ContentAnalyzer() {
            public String key() { return key; }
            public String displayName() { return key; }
            public boolean supports(AnalysisContext context) { return true; }
            public List<RiskFinding> analyze(AnalysisContext context) { return body.get(); }
        };
    }
}
