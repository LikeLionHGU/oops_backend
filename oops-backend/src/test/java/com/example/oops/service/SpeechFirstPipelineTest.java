package com.example.oops.service;

import com.example.oops.analyzer.*;
import com.example.oops.client.*;
import com.example.oops.config.*;
import com.example.oops.domain.*;
import com.example.oops.dto.AnalysisWarningDto;
import com.example.oops.fusion.FindingFusionService;
import com.example.oops.genre.GenreDetector;
import com.example.oops.repository.VideoRepository;
import com.example.oops.screentext.ScreenTextService;
import com.example.oops.transcript.TranscriptService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real pipeline, mocked providers. No video, native OCR or paid model calls. */
class SpeechFirstPipelineTest {
    static class Fixture {
        final Video video = Video.builder().filename("synthetic.mp4").build();
        final TranscriptService transcript = mock(TranscriptService.class);
        final ScreenTextService screens = mock(ScreenTextService.class);
        final AnalysisServerClient worker = mock(AnalysisServerClient.class);
        final JobProgressService progress = mock(JobProgressService.class);
        final AnalysisResultWriter writer = mock(AnalysisResultWriter.class);
        final ContentAnalyzer speech = mock(ContentAnalyzer.class), screen = mock(ContentAnalyzer.class);
        final AnalysisPipeline pipeline;
        Fixture() {
            video.assignGenre(ContentGenre.GENERAL);
            var ai = mock(OpenAiClient.class);
            var usage = mock(OpenAiClient.TokenUsage.class);
            when(usage.isEmpty()).thenReturn(true);
            when(usage.pricing()).thenReturn(mock(OpenAiProperties.Pricing.class));
            when(ai.videoUsage()).thenReturn(usage);
            var videos = mock(VideoRepository.class);
            when(videos.findById(1L)).thenReturn(Optional.of(video));
            when(progress.begin(11L)).thenReturn(1L);
            when(transcript.extract(video)).thenReturn(List.of(new TranscriptSegment(video, 0, 1000, "일상 대화")));
            when(screens.extract(video, 11L)).thenReturn(List.of());
            when(speech.key()).thenReturn("speech-review");
            when(speech.supports(any())).thenReturn(true);
            when(speech.analyze(any())).thenReturn(List.of());
            when(screen.key()).thenReturn("screen-text-review");
            when(screen.supports(any())).thenReturn(false);
            var fusion = mock(FindingFusionService.class);
            when(fusion.fuse(anyList())).thenReturn(List.of());
            when(writer.save(eq(11L), any())).thenReturn(true);
            pipeline = new AnalysisPipeline(List.of(speech, screen), new OopsProperties(null,
                    new OopsProperties.Analysis(List.of("speech-review", "screen-text-review")), null),
                    transcript, screens, fusion, mock(GenreDetector.class), worker,
                    new ReportBuilder(), progress, videos, ai, writer);
        }
        AnalysisResultWriter.Result result() {
            var capture = ArgumentCaptor.forClass(AnalysisResultWriter.Result.class);
            verify(writer).save(eq(11L), capture.capture());
            return capture.getValue();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void screenExtractionRequiresExplicitOptIn(boolean enabled) {
        var f = new Fixture();
        ReflectionTestUtils.setField(f.pipeline, "fullScreenOcrEnabled", enabled);
        f.pipeline.run(11L);
        verify(f.speech).analyze(any());
        verify(f.progress).update(eq(11L), eq(AnalysisStage.TEXT_RISK), eq(enabled ? 62 : 80), anyString());
        verify(f.screens, times(enabled ? 1 : 0)).extract(f.video, 11L);
        var result = f.result();
        var ocr = result.coverage().stream().filter(c -> c.getStep() == CoverageStep.OCR).findFirst().orElseThrow();
        assertThat(ocr.getStatus()).isEqualTo(enabled ? AnalyzerStatus.SUCCESS : AnalyzerStatus.NOT_ENABLED);
        if (!enabled) {
            verify(f.screen, never()).supports(any());
            verify(f.worker, never()).lastOcrAssessment();
            verify(f.progress, never()).update(eq(11L), eq(AnalysisStage.OCR), anyInt());
            assertThat(result.screenTexts()).isEmpty();
            var review = result.coverage().stream().filter(c -> c.getStep() == CoverageStep.SCREEN_TEXT_REVIEW).findFirst().orElseThrow();
            assertThat(review.getStatus()).isEqualTo(AnalyzerStatus.NOT_ENABLED);
            assertThat(AnalysisWarningDto.from(ocr).code()).isEqualTo("OCR_NOT_ENABLED");
            assertThat(ocr.needsWarning()).isTrue();
            assertThat(review.needsWarning()).isTrue();
            assertThat(ocr.getMessage()).contains("화면에만 있는 표현·개인정보는 확인하지 않았습니다");
            var service = mock(AnalysisService.class, CALLS_REAL_METHODS);
            com.example.oops.dto.CoverageDto dto = ReflectionTestUtils.invokeMethod(service, "toCoverage",
                    new ArrayList<>(result.coverage()));
            assertThat(dto.screenTextAnalyzed()).isFalse();
            assertThat(AnalysisWarningDto.from(review).code()).isEqualTo("SCREEN_TEXT_REVIEW_NOT_ENABLED");
        }
    }

    @Test void sttFailureDoesNotSilentlyStartFullScreenOcrOrPublishEmptySuccess() {
        var f = new Fixture();
        when(f.transcript.extract(f.video)).thenReturn(List.of());
        f.pipeline.run(11L);
        verify(f.screens, never()).extract(any(), anyLong());
        verify(f.writer, never()).save(anyLong(), any());
        verify(f.speech, never()).analyze(any());
        verify(f.progress).fail(eq(11L), eq("ANALYSIS_FAILED"), contains("전체 화면 글자 검수는 선택되지 않았습니다"));
    }
}
