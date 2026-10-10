package com.example.oops.controller;

import com.example.oops.common.BusinessException;
import com.example.oops.repository.VideoRepository;
import com.example.oops.service.ReviewDiagnosticsStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewDiagnosticsControllerTest {
    private final VideoRepository videos = mock(VideoRepository.class);
    private final ReviewDiagnosticsStore store = new ReviewDiagnosticsStore(true);
    private final com.example.oops.repository.TranscriptSegmentRepository transcripts = mock(com.example.oops.repository.TranscriptSegmentRepository.class);
    private final com.example.oops.transcript.SttReviewPlanner planner = new com.example.oops.transcript.SttReviewPlanner();
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(ReviewDiagnosticsController.class)
                .withBean(VideoRepository.class, () -> videos)
                .withBean(ReviewDiagnosticsStore.class, () -> store)
                .withBean(com.example.oops.repository.TranscriptSegmentRepository.class, () -> transcripts)
                .withBean(com.example.oops.transcript.SttReviewPlanner.class, () -> planner);
    }
    @Test void defaultAndExplicitFalseDoNotExposeController() {
        runner().run(ctx -> assertThat(ctx).doesNotHaveBean(ReviewDiagnosticsController.class));
        runner().withPropertyValues("oops.analysis.review-diagnostics-enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ReviewDiagnosticsController.class));
    }
    @Test void enabledReturnsUnavailableForHistoricRunsAndSnapshotForNewRuns() {
        when(videos.existsById(5L)).thenReturn(true);
        runner().withPropertyValues("oops.analysis.review-diagnostics-enabled=true").run(ctx -> {
            var controller = ctx.getBean(ReviewDiagnosticsController.class);
            assertThat(controller.diagnostics("5").data().available()).isFalse();
            store.recordAfterCommit(5L, 11L, "test", List.of());
            assertThat(controller.diagnostics("5").data().snapshot().analysisJobId()).isEqualTo(11L);
        });
    }
    @Test void missingVideoCannotExposeRetainedSnapshot() {
        store.recordAfterCommit(5L, 11L, "test", List.of());
        var controller = new ReviewDiagnosticsController(videos, store, transcripts, planner);
        assertThatThrownBy(() -> controller.diagnostics("5")).isInstanceOf(BusinessException.class);
    }
    @Test void planningUnavailableIsNotAnErrorFreeTranscriptVerdict() {
        when(videos.findById(5L)).thenReturn(java.util.Optional.of(mock(com.example.oops.domain.Video.class)));
        when(transcripts.findByVideoIdOrderByStartMsAsc(5L)).thenReturn(List.of());
        var controller = new ReviewDiagnosticsController(videos, store, transcripts, planner);
        var result = controller.sttReviewPlan("5").data();
        assertThat(result.state()).isEqualTo("DIAGNOSTICS_UNAVAILABLE");
        assertThat(result.additionalCalls()).isZero(); assertThat(result.executionEnabled()).isFalse();
        verify(transcripts, never()).deleteByVideoId(anyLong());
    }
    @Test void missingVideoCannotExposeAudioPlan() {
        var controller = new ReviewDiagnosticsController(videos, store, transcripts, planner);
        assertThatThrownBy(() -> controller.sttReviewPlan("5")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(transcripts);
    }
}
