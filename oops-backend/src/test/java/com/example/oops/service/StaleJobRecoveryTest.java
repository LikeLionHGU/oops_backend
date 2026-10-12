package com.example.oops.service;

import com.example.oops.domain.*;
import com.example.oops.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StaleJobRecoveryTest {
    @Test void disabledRecoveryDoesNotReadOrMutateJobs() {
        var jobs=mock(AnalysisJobRepository.class); var service=mock(InterruptedJobRecoveryService.class);
        new StaleJobRecovery(jobs, service, false).recover(); verifyNoInteractions(jobs, service);
    }
    @Test void oneJobFailureDoesNotPreventOtherRecoveryAttempts() {
        var jobs=mock(AnalysisJobRepository.class); var service=mock(InterruptedJobRecoveryService.class);
        when(jobs.findRecoveryCandidateIds(any(), anyList())).thenReturn(List.of(1L, 2L));
        when(service.recover(eq(1L), any())).thenThrow(new IllegalStateException("fixture"));
        when(service.recover(eq(2L), any())).thenReturn(true);
        new StaleJobRecovery(jobs, service, true).recover();
        verify(service).recover(eq(1L), any()); verify(service).recover(eq(2L), any());
    }
    @ParameterizedTest @ValueSource(strings={"createdAt", "startedAt", "updatedAt"})
    void freshActivityIsRecheckedAfterLocks(String field) {
        var jobs=mock(AnalysisJobRepository.class); var videos=mock(VideoRepository.class);
        var cutoff=LocalDateTime.now(); var old=cutoff.minusHours(1);
        var video=Video.builder().filename("fixture").build();
        var job=new AnalysisJob(video); job.start();
        for (var timestamp:List.of("createdAt", "startedAt", "updatedAt")) ReflectionTestUtils.setField(job, timestamp, old);
        ReflectionTestUtils.setField(job, field, cutoff);
        when(jobs.findVideoIdById(1L)).thenReturn(Optional.of(2L));
        when(videos.findByIdForUpdate(2L)).thenReturn(Optional.of(video));
        when(jobs.findByIdForUpdate(1L)).thenReturn(Optional.of(job));
        assertThat(new InterruptedJobRecoveryService(jobs, videos).recover(1L, cutoff)).isFalse();
        assertThat(job.getStatus()).isEqualTo(AnalysisStatus.PROCESSING);
        var order=inOrder(jobs, videos);
        order.verify(jobs).findVideoIdById(1L); order.verify(videos).findByIdForUpdate(2L); order.verify(jobs).findByIdForUpdate(1L);
        verify(jobs, never()).findTopByVideoIdOrderByIdDesc(anyLong());
    }
}
