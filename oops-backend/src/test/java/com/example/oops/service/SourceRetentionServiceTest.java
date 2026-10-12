package com.example.oops.service;

import com.example.oops.domain.*;
import com.example.oops.repository.*;
import com.example.oops.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceRetentionServiceTest {
    private final VideoRepository videos = mock(VideoRepository.class);
    private final AnalysisJobRepository jobs = mock(AnalysisJobRepository.class);
    private final StorageService storage = mock(StorageService.class);
    private final SourceRetentionService service = new SourceRetentionService(videos, jobs, storage);
    private final LocalDateTime cutoff = LocalDateTime.of(2026, 10, 12, 12, 0);

    private Video video(AnalysisStatus status, LocalDateTime created) {
        var v = Video.builder().sourceType(SourceType.UPLOAD).filename("fixture.mp4").build();
        v.updateStatus(status);
        ReflectionTestUtils.setField(v, "createdAt", created);
        when(videos.findByIdForUpdate(1L)).thenReturn(Optional.of(v));
        return v;
    }

    @Test void expiredTerminalVideoIsDeletedOnlyAfterLockedRecheck() {
        video(AnalysisStatus.FAILED, cutoff.minusHours(1));
        assertThat(service.purgeIfExpired(1L, cutoff)).isTrue();
        var order = inOrder(videos, jobs, storage);
        order.verify(videos).findByIdForUpdate(1L);
        order.verify(jobs).existsByVideoIdAndStatusIn(eq(1L), anyList());
        order.verify(storage).deleteSourceFile(1L);
    }

    @Test void retryStartedAfterSchedulerSnapshotIsPreserved() {
        video(AnalysisStatus.PROCESSING, cutoff.minusHours(1));
        assertThat(service.purgeIfExpired(1L, cutoff)).isFalse();
        verifyNoInteractions(storage);
    }

    @Test void queuedJobWinsEvenIfVideoStatusHasNotCaughtUp() {
        video(AnalysisStatus.FAILED, cutoff.minusHours(1));
        when(jobs.existsByVideoIdAndStatusIn(eq(1L), anyList())).thenReturn(true);
        assertThat(service.purgeIfExpired(1L, cutoff)).isFalse();
        verifyNoInteractions(storage);
    }

    @Test void recentOrMissingCreationTimeIsNotEligible() {
        video(AnalysisStatus.COMPLETED, cutoff);
        assertThat(service.purgeIfExpired(1L, cutoff)).isFalse();
        video(AnalysisStatus.COMPLETED, null);
        assertThat(service.purgeIfExpired(1L, cutoff)).isFalse();
        verifyNoInteractions(storage);
    }

    @Test void missingVideoIsNotDeleted() {
        when(videos.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertThat(service.purgeIfExpired(1L, cutoff)).isFalse();
        verifyNoInteractions(storage);
    }
}
