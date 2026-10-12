package com.example.oops.service;

import com.example.oops.domain.*;
import com.example.oops.repository.*;
import com.example.oops.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class JobFrameRetentionTest {
    private final VideoRepository videos=mock(VideoRepository.class);
    private final AnalysisJobRepository jobs=mock(AnalysisJobRepository.class);
    private final VideoFrameRepository frames=mock(VideoFrameRepository.class);
    private final StorageService storage=mock(StorageService.class);
    private final JobFrameRetentionService service=new JobFrameRetentionService(videos, jobs, frames, storage);
    private final StorageService.JobFrames owner=new StorageService.JobFrames(1L, 10L);
    private final Instant markerThreshold=Instant.now().minusSeconds(86400);
    private final LocalDateTime jobThreshold=LocalDateTime.now().minusHours(24);

    private AnalysisJob fixture() {
        var video=Video.builder().filename("fixture.mp4").build();
        org.springframework.test.util.ReflectionTestUtils.setField(video, "id", 1L);
        var job=new AnalysisJob(video); job.fail("TEST", "test");
        org.springframework.test.util.ReflectionTestUtils.setField(job, "finishedAt", jobThreshold.minusHours(1));
        when(videos.findByIdForUpdate(1L)).thenReturn(Optional.of(video));
        when(jobs.findById(10L)).thenReturn(Optional.of(job));
        when(storage.frameDir(1L)).thenReturn(Path.of("/test/frames/1"));
        return job;
    }
    @ParameterizedTest @EnumSource(value=AnalysisStatus.class, names={"COMPLETED", "FAILED", "CANCELLED"})
    void collectsExpiredUnreferencedTerminalJobs(AnalysisStatus status) throws Exception {
        var job=fixture(); org.springframework.test.util.ReflectionTestUtils.setField(job, "status", status);
        when(storage.deleteExpiredJobFrames(1L, 10L, markerThreshold)).thenReturn(true);
        assertThat(service.purge(owner, markerThreshold, jobThreshold)).isTrue();
        var order=inOrder(videos, jobs, frames, storage);
        order.verify(videos).findByIdForUpdate(1L);
        order.verify(jobs).existsByVideoIdAndStatusIn(eq(1L), anyList());
        order.verify(jobs).findById(10L);
        order.verify(storage).frameDir(1L);
        order.verify(frames).findByVideoIdOrderByTimeMsAsc(1L);
        order.verify(storage).deleteExpiredJobFrames(1L, 10L, markerThreshold);
    }
    @Test void anyRunningJobProtectsAllVideoFolders() throws Exception {
        fixture(); when(jobs.existsByVideoIdAndStatusIn(eq(1L), anyList())).thenReturn(true);
        assertThat(service.purge(owner, markerThreshold, jobThreshold)).isFalse(); verifyNoInteractions(storage, frames);
    }
    @Test void persistedFrameReferenceProtectsRetainedReportFolder() throws Exception {
        var job=fixture();
        when(frames.findByVideoIdOrderByTimeMsAsc(1L)).thenReturn(List.of(new VideoFrame(job.getVideo(), 0, "frames/1/jobs/10/frame.jpg", "image/jpeg")));
        when(storage.resolve("frames/1/jobs/10/frame.jpg")).thenReturn(Path.of("/test/frames/1/jobs/10/frame.jpg"));
        assertThat(service.purge(owner, markerThreshold, jobThreshold)).isFalse();
        verify(storage, never()).deleteExpiredJobFrames(anyLong(), anyLong(), any());
    }
    @Test void freshOrUnknownOwnerIsRetained() throws Exception {
        var job=fixture(); org.springframework.test.util.ReflectionTestUtils.setField(job, "finishedAt", jobThreshold.plusHours(1));
        assertThat(service.purge(owner, markerThreshold, jobThreshold)).isFalse();
        when(jobs.findById(10L)).thenReturn(Optional.empty());
        assertThat(service.purge(owner, markerThreshold, jobThreshold)).isFalse(); verifyNoInteractions(storage, frames);
    }
    @Test void disabledSchedulerDoesNotInspectOrDeleteFiles() {
        new JobFrameCleanupScheduler(storage, service, 0).cleanUp(); verifyNoInteractions(storage, videos, jobs, frames);
    }
    @Test void enabledSchedulerAdvancesPastProtectedBatchAndWraps() {
        when(storage.expiredJobFrames(any(), eq(0L))).thenReturn(java.util.Collections.nCopies(1000, owner));
        when(storage.expiredJobFrames(any(), eq(1000L))).thenReturn(List.of());
        var scheduler=new JobFrameCleanupScheduler(storage, mock(JobFrameRetentionService.class), 1);
        scheduler.cleanUp(); scheduler.cleanUp(); scheduler.cleanUp();
        verify(storage, times(2)).expiredJobFrames(any(), eq(0L));
        verify(storage).expiredJobFrames(any(), eq(1000L));
    }
}
