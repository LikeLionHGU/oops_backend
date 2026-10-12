package com.example.oops.service;

import com.example.oops.domain.*;
import com.example.oops.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobProgressLockOrderTest {
    private final AnalysisJobRepository jobs=mock(AnalysisJobRepository.class);
    private final VideoRepository videos=mock(VideoRepository.class);
    private final ProgressPublisher publisher=mock(ProgressPublisher.class);
    private final JobProgressService service=new JobProgressService(jobs, publisher, videos);

    private AnalysisJob fixture() {
        var video=Video.builder().filename("lock-order.mp4").build();
        ReflectionTestUtils.setField(video, "id", 1L);
        var job=new AnalysisJob(video); ReflectionTestUtils.setField(job, "id", 2L);
        when(jobs.findVideoIdById(2L)).thenReturn(Optional.of(1L));
        when(videos.findByIdForUpdate(1L)).thenReturn(Optional.of(video));
        when(jobs.findFirstByVideoIdOrderByIdDesc(1L)).thenReturn(Optional.of(job));
        return job;
    }
    @ParameterizedTest @ValueSource(strings={"BEGIN", "UPDATE", "COMPLETE", "FAIL"})
    void mutationsLockVideoBeforeLatestJob(String operation) {
        var job=fixture(); if (!operation.equals("BEGIN")) job.start();
        switch(operation) {
            case "BEGIN" -> assertThat(service.begin(2L)).isEqualTo(1L);
            case "UPDATE" -> service.update(2L, AnalysisStage.OCR, 35);
            case "COMPLETE" -> assertThat(service.complete(2L)).isTrue();
            case "FAIL" -> assertThat(service.fail(2L, "TEST", "fixture")).isTrue();
        }
        var ordered=inOrder(jobs, videos);
        ordered.verify(jobs).findVideoIdById(2L);
        ordered.verify(videos).findByIdForUpdate(1L);
        ordered.verify(jobs).findFirstByVideoIdOrderByIdDesc(1L);
        verify(jobs, never()).findByIdForUpdate(2L);
    }
    @Test void supersededJobCannotChangeVideoOrEmitProgress() {
        var old=fixture(); old.start(); old.getVideo().updateStatus(AnalysisStatus.PENDING);
        var latest=new AnalysisJob(old.getVideo()); ReflectionTestUtils.setField(latest, "id", 3L);
        when(jobs.findFirstByVideoIdOrderByIdDesc(1L)).thenReturn(Optional.of(latest));
        assertThat(service.begin(2L)).isNull();
        assertThat(service.complete(2L)).isFalse();
        assertThat(service.fail(2L, "TEST", "fixture")).isFalse();
        service.update(2L, AnalysisStage.OCR, 35);
        assertThat(old.getStatus()).isEqualTo(AnalysisStatus.PROCESSING);
        assertThat(old.getVideo().getStatus()).isEqualTo(AnalysisStatus.PENDING);
        verify(jobs, never()).flush(); verifyNoInteractions(publisher);
    }
    @ParameterizedTest @EnumSource(AnalysisStatus.class)
    void onlyLatestProcessingJobCanContinue(AnalysisStatus status) {
        var job=fixture(); ReflectionTestUtils.setField(job, "status", status);
        when(jobs.findById(2L)).thenReturn(Optional.of(job));
        when(jobs.findTopByVideoIdOrderByIdDesc(1L)).thenReturn(Optional.of(job));
        assertThat(service.isStopped(2L)).isEqualTo(status != AnalysisStatus.PROCESSING);
        if (status==AnalysisStatus.PROCESSING) {
            var next=new AnalysisJob(job.getVideo()); ReflectionTestUtils.setField(next, "id", 3L);
            when(jobs.findTopByVideoIdOrderByIdDesc(1L)).thenReturn(Optional.of(next));
            assertThat(service.isStopped(2L)).isTrue();
        }
    }
    @Test void missingJobStopsWithoutTryingToLockVideo() {
        assertThat(service.begin(2L)).isNull();
        assertThat(service.isStopped(2L)).isTrue();
        verifyNoInteractions(videos, publisher);
    }
}
