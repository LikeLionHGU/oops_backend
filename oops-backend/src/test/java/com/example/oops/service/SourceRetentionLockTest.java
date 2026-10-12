package com.example.oops.service;

import com.example.oops.domain.*;
import com.example.oops.repository.VideoRepository;
import com.example.oops.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class SourceRetentionLockTest {
    @Autowired VideoRepository videos;
    @Autowired SourceRetentionService retention;
    @Autowired PlatformTransactionManager manager;
    @Autowired JobFrameRetentionService frameRetention;
    @Autowired com.example.oops.repository.AnalysisJobRepository jobs;
    @Autowired JobProgressService progress;
    @MockitoBean StorageService storage;

    @Test void lockedRetryStatusChangeIsVisibleBeforeAnySourceDeletion() throws Exception {
        var tx = new TransactionTemplate(manager);
        Long id = tx.execute(s -> {
            var v = Video.builder().sourceType(SourceType.UPLOAD).filename("lock-fixture.mp4").build();
            v.updateStatus(AnalysisStatus.FAILED);
            return videos.saveAndFlush(v).getId();
        });
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempted = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var retry = pool.submit(() -> tx.executeWithoutResult(s -> {
                var v = videos.findByIdForUpdate(id).orElseThrow();
                v.updateStatus(AnalysisStatus.PROCESSING);
                videos.flush();
                held.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test lock timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var purge = pool.submit(() -> {
                attempted.countDown();
                return retention.purgeIfExpired(id, LocalDateTime.now().plusHours(1));
            });
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> purge.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            verifyNoInteractions(storage);
            release.countDown();
            retry.get(5, TimeUnit.SECONDS);
            assertThat(purge.get(5, TimeUnit.SECONDS)).isFalse();
            verifyNoInteractions(storage);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
    @Test void frameCleanupWaitsForRetryCommitAndThenKeepsEveryFolder() throws Exception {
        var tx=new TransactionTemplate(manager);
        var owner=tx.execute(s -> {
            var video=videos.save(Video.builder().sourceType(SourceType.UPLOAD).filename("frame-lock.mp4").build());
            var job=new AnalysisJob(video); job.fail("TEST", "fixture");
            jobs.saveAndFlush(job);
            return new StorageService.JobFrames(video.getId(), job.getId());
        });
        var held=new CountDownLatch(1); var release=new CountDownLatch(1); var attempted=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var retry=pool.submit(() -> tx.executeWithoutResult(s -> {
                var video=videos.findByIdForUpdate(owner.videoId()).orElseThrow();
                jobs.saveAndFlush(new AnalysisJob(video)); video.updateStatus(AnalysisStatus.PENDING);
                held.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var purge=pool.submit(() -> {
                attempted.countDown();
                return frameRetention.purge(owner, java.time.Instant.now().plusSeconds(3600), LocalDateTime.now().plusHours(1));
            });
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> purge.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            verifyNoInteractions(storage);
            release.countDown(); retry.get(5, TimeUnit.SECONDS);
            assertThat(purge.get(5, TimeUnit.SECONDS)).isFalse(); verifyNoInteractions(storage);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void waitingBeginDoesNotHoldJobLockBeforeVideoLock() throws Exception {
        var tx=new TransactionTemplate(manager);
        var owner=tx.execute(s -> {
            var video=videos.save(Video.builder().sourceType(SourceType.UPLOAD).filename("begin-lock.mp4").build());
            var job=jobs.saveAndFlush(new AnalysisJob(video));
            return new StorageService.JobFrames(video.getId(), job.getId());
        });
        var held=new CountDownLatch(1); var release=new CountDownLatch(1); var entering=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(3);
        try {
            var holding=pool.submit(() -> tx.executeWithoutResult(s -> {
                videos.findByIdForUpdate(owner.videoId()).orElseThrow(); held.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var begin=pool.submit(() -> { entering.countDown(); return progress.begin(owner.jobId()); });
            assertThat(entering.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> begin.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            // A Video holder can still obtain Job: begin must not hold the opposite lock.
            var jobLock=pool.submit(() -> tx.execute(s -> jobs.findByIdForUpdate(owner.jobId()).orElseThrow().getId()));
            assertThat(jobLock.get(1, TimeUnit.SECONDS)).isEqualTo(owner.jobId());
            release.countDown(); holding.get(5, TimeUnit.SECONDS);
            assertThat(begin.get(5, TimeUnit.SECONDS)).isEqualTo(owner.videoId());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
