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
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(ReviewDiagnosticsController.class)
                .withBean(VideoRepository.class, () -> videos)
                .withBean(ReviewDiagnosticsStore.class, () -> store);
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
        var controller = new ReviewDiagnosticsController(videos, store);
        assertThatThrownBy(() -> controller.diagnostics("5")).isInstanceOf(BusinessException.class);
    }
}
