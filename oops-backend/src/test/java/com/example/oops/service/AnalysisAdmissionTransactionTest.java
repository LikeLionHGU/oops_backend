package com.example.oops.service;

import com.example.oops.client.AnalysisServerClient;
import com.example.oops.domain.*;
import com.example.oops.dto.VideoUploadResponse;
import com.example.oops.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real admission and DB, no media/AI; a single connection catches nested-tx deadlocks. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:admission-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.hikari.maximum-pool-size=1", "spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.connection-timeout=1000",
        "oops.analysis-executor.core-pool-size=1", "oops.analysis-executor.max-pool-size=1",
        "oops.analysis-executor.queue-capacity=0"})
@ActiveProfiles("test")
class AnalysisAdmissionTransactionTest {
    @Autowired AnalysisService analysis;
    @Autowired JobProgressService progress;
    @Autowired VideoRepository videos;
    @Autowired AnalysisJobRepository jobs;
    @Autowired PlatformTransactionManager manager;
    @Autowired @Qualifier("analysisExecutor") ThreadPoolTaskExecutor executor;
    @MockitoBean AnalysisPipeline pipeline;
    @MockitoBean AnalysisServerClient media;
    @MockitoBean ProgressPublisher publisher;

    @BeforeEach void healthy() { when(media.isHealthy()).thenReturn(true); }

    private Video seed() {
        return videos.saveAndFlush(Video.builder().sourceType(SourceType.UPLOAD).filename("admission-test.mp4").build());
    }

    @Test void fullExecutorCommitsFailureWithOneConnectionAndAccurateResponse() throws Exception {
        assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        var video = seed();
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        executor.execute(() -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { finished.countDown(); }
        });
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var job = analysis.startAnalysis(video.getId());
            assertThat(job.getStatus()).isEqualTo(AnalysisStatus.FAILED);
            assertThat(job.getErrorCode()).isEqualTo("ANALYSIS_QUEUE_FULL");
            assertThat(VideoUploadResponse.of(video, job).status()).isEqualTo(AnalysisStatus.FAILED);
            assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.FAILED);
            assertThat(videos.findById(video.getId()).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.FAILED);
            verify(publisher).publish(job);
            var retry = analysis.retry(video.getId());
            assertThat(retry.status()).isEqualTo(AnalysisStatus.FAILED);
            var latest = jobs.findTopByVideoIdOrderByIdDesc(video.getId()).orElseThrow();
            assertThat(latest.getId()).isNotEqualTo(job.getId());
            assertThat(latest.getErrorCode()).isEqualTo("ANALYSIS_QUEUE_FULL");
            verify(publisher, times(2)).publish(any(AnalysisJob.class));
            verifyNoInteractions(pipeline);
        } finally {
            release.countDown();
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
            awaitIdle();
        }
    }

    @Test void acceptedWorkerSeesCommittedJobAndCanUseOnlyConnection() throws Exception {
        var video = seed();
        var result = new CompletableFuture<Long>();
        doAnswer(call -> {
            try { result.complete(progress.begin(call.getArgument(0))); }
            catch (Throwable error) { result.completeExceptionally(error); }
            return null;
        }).when(pipeline).run(anyLong());
        var job = analysis.startAnalysis(video.getId());
        assertThat(result.get(3, TimeUnit.SECONDS)).isEqualTo(video.getId());
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.PROCESSING);
        assertThat(videos.findById(video.getId()).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.PROCESSING);
        awaitIdle();
    }

    @Test void rolledBackAdmissionDoesNotLeaveJobOrRunPipeline() throws Exception {
        var video = seed();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            analysis.startAnalysis(video.getId());
            verifyNoInteractions(pipeline);
            tx.setRollbackOnly();
        });
        awaitIdle();
        assertThat(jobs.findTopByVideoIdOrderByIdDesc(video.getId())).isEmpty();
        verifyNoInteractions(pipeline, publisher);
    }

    private void awaitIdle() throws InterruptedException {
        // Wait for capacity rather than racing the next test against task return.
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (executor.getActiveCount() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(executor.getActiveCount()).isZero();
    }
}
