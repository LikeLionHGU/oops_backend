package com.example.oops.service;

import com.example.oops.domain.*;
import com.example.oops.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

/** Separate memory DB; no runtime media, keys or model calls. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:recovery-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class InterruptedJobRecoveryTest {
    @Autowired VideoRepository videos;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean AnalysisJobRepository jobs;
    @Autowired AnalysisReportRepository reports;
    @Autowired InterruptedJobRecoveryService recovery;
    @Autowired PlatformTransactionManager manager;

    private Long seed(AnalysisStatus status) {
        return new TransactionTemplate(manager).execute(tx -> {
            var video=videos.save(Video.builder().sourceType(SourceType.UPLOAD).filename("recovery-fixture.mp4").build());
            var job=new AnalysisJob(video);
            if (status==AnalysisStatus.PROCESSING || status==AnalysisStatus.COMPLETED) job.start();
            if (status==AnalysisStatus.COMPLETED) job.complete();
            if (status==AnalysisStatus.CANCELLED) job.cancel();
            if (status==AnalysisStatus.FAILED) job.fail("TEST", "existing failure");
            video.updateStatus(status);
            return jobs.saveAndFlush(job).getId();
        });
    }
    @ParameterizedTest @EnumSource(value=AnalysisStatus.class, names={"PENDING", "PROCESSING"})
    void interruptedLatestJobAndVideoFailTogetherWithoutDeletingReport(AnalysisStatus status) {
        Long id=seed(status);
        Long videoId=new TransactionTemplate(manager).execute(tx -> {
            var video=jobs.findById(id).orElseThrow().getVideo();
            reports.save(new AnalysisReport(video, 0, 0, "retained report"));
            return video.getId();
        });
        // Synthetic cutoff avoids modifying the real application startup time.
        assertThat(recovery.recover(id, LocalDateTime.now().plusHours(1))).isTrue();
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(videos.findById(videoId).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(reports.findByVideoId(videoId).orElseThrow().getSummary()).isEqualTo("retained report");
        assertThat(recovery.recover(id, LocalDateTime.now().plusHours(1))).isFalse();
    }
    @ParameterizedTest @EnumSource(value=AnalysisStatus.class, names={"COMPLETED", "CANCELLED", "FAILED"})
    void alreadyTerminatedCandidateIsNotOverwritten(AnalysisStatus status) {
        Long id=seed(status);
        assertThat(recovery.recover(id, LocalDateTime.now().plusHours(1))).isFalse();
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(status);
    }
    @Test void supersededInterruptedJobDoesNotChangeNewerJobOrVideo() {
        Long old=seed(AnalysisStatus.PROCESSING);
        Long next=new TransactionTemplate(manager).execute(tx -> {
            var video=jobs.findById(old).orElseThrow().getVideo();
            video.updateStatus(AnalysisStatus.COMPLETED);
            var job=new AnalysisJob(video); job.start(); job.complete();
            return jobs.saveAndFlush(job).getId();
        });
        assertThat(recovery.recover(old, LocalDateTime.now().plusHours(1))).isTrue();
        assertThat(jobs.findById(old).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.FAILED);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            var latest=jobs.findById(next).orElseThrow();
            assertThat(latest.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
            assertThat(latest.getVideo().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        });
    }
    @Test void jobsCreatedAfterCutoffAndMissingJobsAreSkipped() {
        Long id=seed(AnalysisStatus.PROCESSING);
        var cutoff=LocalDateTime.now().minusHours(1);
        assertThat(jobs.findRecoveryCandidateIds(cutoff, java.util.List.of(AnalysisStatus.PENDING, AnalysisStatus.PROCESSING))).doesNotContain(id);
        assertThat(recovery.recover(id, cutoff)).isFalse();
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.PROCESSING);
        assertThat(recovery.recover(Long.MAX_VALUE, cutoff)).isFalse();
    }
    @Test void flushFailureRollsBackJobAndVideoTogether() {
        Long id=seed(AnalysisStatus.PENDING);
        org.mockito.Mockito.doThrow(new IllegalStateException("fixture flush failure")).when(jobs).flush();
        assertThatThrownBy(() -> recovery.recover(id, LocalDateTime.now().plusHours(1))).isInstanceOf(IllegalStateException.class);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            var job=jobs.findById(id).orElseThrow();
            assertThat(job.getStatus()).isEqualTo(AnalysisStatus.PENDING);
            assertThat(job.getVideo().getStatus()).isEqualTo(AnalysisStatus.PENDING);
            assertThat(job.getErrorCode()).isNull();
        });
    }
    @Test void cancellationCommittedWhileRecoveryWaitsIsPreserved() throws Exception {
        Long id=seed(AnalysisStatus.PROCESSING);
        Long videoId=new TransactionTemplate(manager).execute(tx -> jobs.findById(id).orElseThrow().getVideo().getId());
        var held=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        var attempting=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var cancel=pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                var video=videos.findByIdForUpdate(videoId).orElseThrow();
                var job=jobs.findByIdForUpdate(id).orElseThrow(); job.cancel(); video.updateStatus(AnalysisStatus.CANCELLED);
                jobs.flush(); held.countDown();
                try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertThat(held.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var recover=pool.submit(() -> { attempting.countDown(); return recovery.recover(id, LocalDateTime.now().plusHours(1)); });
            assertThat(attempting.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> recover.get(200, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown(); cancel.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(recover.get(5, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
            assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
            assertThat(videos.findById(videoId).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
