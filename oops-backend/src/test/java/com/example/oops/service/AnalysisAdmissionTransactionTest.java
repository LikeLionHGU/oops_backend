package com.example.oops.service;

import com.example.oops.client.AnalysisServerClient;
import com.example.oops.common.BusinessException;
import com.example.oops.common.ErrorCode;
import com.example.oops.domain.*;
import com.example.oops.dto.VideoUploadResponse;
import com.example.oops.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    @Autowired AnalysisAdmissionService admission;
    @Autowired JobProgressService progress;
    @Autowired VideoRepository videos;
    @Autowired AnalysisJobRepository jobs;
    @Autowired TranscriptReplayService replay;
    @Autowired TranscriptSegmentRepository transcripts;
    @Autowired com.example.oops.transcript.TranscriptService transcriptService;
    @Autowired AnalysisResultWriter resultWriter;
    @Autowired PlatformTransactionManager manager;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired @Qualifier("analysisExecutor") ThreadPoolTaskExecutor executor;
    @MockitoBean AnalysisPipeline pipeline;
    @MockitoBean AnalysisServerClient media;
    @MockitoBean ProgressPublisher publisher;

    @BeforeEach void healthy() { when(media.isHealthy()).thenReturn(true); }

    private Video seed() {
        return videos.saveAndFlush(Video.builder().sourceType(SourceType.UPLOAD).filename("admission-test.mp4").build());
    }

    @Test void replayWorkerSeesCommittedOwnedTranscriptWithOneConnection() throws Exception {
        var source=seed();source.updateStatus(AnalysisStatus.COMPLETED);videos.saveAndFlush(source);
        transcripts.saveAndFlush(new TranscriptSegment(source,0,1000,"저장된 전체 전사"));
        var observed=new CompletableFuture<Long>();
        doAnswer(call->{
            try {
                Long id=progress.begin(call.getArgument(0));
                assertThat(transcripts.findByVideoIdOrderByStartMsAscIdAsc(id)).extracting(TranscriptSegment::getText)
                        .containsExactly("저장된 전체 전사");
                var target=videos.findById(id).orElseThrow();
                var copied=transcriptService.extract(target);
                assertThat(resultWriter.save(call.getArgument(0),new AnalysisResultWriter.Result(target,ContentGenre.GENERAL,
                        copied,java.util.List.of(),java.util.List.of(),java.util.List.of(),0,"무료 저장 경로 검증","test-model-not-called",java.util.List.of())))
                        .isTrue();
                observed.complete(id);
            } catch(Throwable error) {observed.completeExceptionally(error);}
            return null;
        }).when(pipeline).run(anyLong());
        var result=replay.create(source.getId());
        assertThat(observed.get(3,TimeUnit.SECONDS)).isEqualTo(Long.parseLong(result.videoId()));
        assertThat(videos.findById(source.getId()).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(analysis.getReport(Long.parseLong(result.videoId())).events()).isEmpty();
        assertThat(analysis.getTranscript(Long.parseLong(result.videoId()))).hasSize(1);
        verify(media,never()).transcribe(any());awaitIdle();
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
            admission.admit(video.getId(), false);
            verifyNoInteractions(pipeline);
            tx.setRollbackOnly();
        });
        awaitIdle();
        assertThat(jobs.findTopByVideoIdOrderByIdDesc(video.getId())).isEmpty();
        verifyNoInteractions(pipeline, publisher);
    }

    @Test void healthCheckHasNoTransactionOrReservedConnection() {
        var video = seed();
        when(media.isHealthy()).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            // With a pool of one, this would time out if preflight retained its connection.
            try (var connection = dataSource.getConnection()) { assertThat(connection.isClosed()).isFalse(); }
            return false;
        });
        assertError(() -> analysis.startAnalysis(video.getId()), ErrorCode.WORKER_UNAVAILABLE);
        assertThat(jobs.findTopByVideoIdOrderByIdDesc(video.getId())).isEmpty();
        assertThat(videos.findById(video.getId()).orElseThrow().getStatus()).isEqualTo(video.getStatus());
        verifyNoInteractions(pipeline, publisher);
    }

    @Test void unavailableWorkerDoesNotResetRetryReviewOrCreateJob() {
        var video = seed();
        var old = new TransactionTemplate(manager).execute(tx -> {
            var current = videos.findByIdForUpdate(video.getId()).orElseThrow();
            current.markReviewCompleted(java.time.LocalDateTime.now());
            current.updateStatus(AnalysisStatus.FAILED);
            var job = new AnalysisJob(current); job.fail("TEST", "existing failure");
            return jobs.saveAndFlush(job).getId();
        });
        when(media.isHealthy()).thenReturn(false);
        assertError(() -> analysis.retry(video.getId()), ErrorCode.WORKER_UNAVAILABLE);
        assertThat(jobs.findTopByVideoIdOrderByIdDesc(video.getId()).orElseThrow().getId()).isEqualTo(old);
        assertThat(videos.findById(video.getId()).orElseThrow().reviewStatusOrDefault()).isEqualTo(ReviewStatus.COMPLETED);
        verifyNoInteractions(pipeline, publisher);
    }

    @Test void invalidPreflightDoesNotCallWorker() {
        assertError(() -> analysis.startAnalysis(Long.MAX_VALUE), ErrorCode.VIDEO_NOT_FOUND);
        var video = seed();
        new TransactionTemplate(manager).executeWithoutResult(tx -> jobs.save(new AnalysisJob(video)));
        assertError(() -> analysis.startAnalysis(video.getId()), ErrorCode.ANALYSIS_IN_PROGRESS);
        assertError(() -> analysis.retry(video.getId()), ErrorCode.INVALID_ANALYSIS_STATE);
        verifyNoInteractions(media, pipeline, publisher);
    }

    @Test void facadeRefusesOuterTransactionsBeforeAnyNetworkCall() {
        var video = seed();
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> analysis.startAnalysis(video.getId())))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> analysis.retry(video.getId())))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        verifyNoInteractions(media, pipeline, publisher);
        assertThat(jobs.findTopByVideoIdOrderByIdDesc(video.getId())).isEmpty();
    }

    @Test void healthWaitDoesNotBlockVideoLockAndAdmissionRechecksRunningJob() throws Exception {
        var video = seed();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var requester = Executors.newSingleThreadExecutor();
        when(media.isHealthy()).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("health wait not released");
            return true;
        });
        try {
            var result = requester.submit(() -> analysis.startAnalysis(video.getId()));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            // This real transaction must get the only connection and Video lock
            // while the other request is still waiting for health.
            var competing = new TransactionTemplate(manager).execute(tx -> {
                var current = videos.findByIdForUpdate(video.getId()).orElseThrow();
                return jobs.saveAndFlush(new AnalysisJob(current)).getId();
            });
            release.countDown();
            assertThatThrownBy(() -> result.get(3, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error.getCause()).getErrorCode())
                            .isEqualTo(ErrorCode.ANALYSIS_IN_PROGRESS));
            assertThat(jobs.findTopByVideoIdOrderByIdDesc(video.getId()).orElseThrow().getId()).isEqualTo(competing);
            verifyNoInteractions(pipeline, publisher);
        } finally { release.countDown(); requester.shutdownNow(); }
    }

    @Test void retryRechecksLatestStatusAfterHealthAndPreservesReview() {
        var video = seed();
        var old = new TransactionTemplate(manager).execute(tx -> {
            var current = videos.findByIdForUpdate(video.getId()).orElseThrow();
            current.markReviewCompleted(java.time.LocalDateTime.now());
            var job = new AnalysisJob(current); job.fail("TEST", "retryable");
            return jobs.saveAndFlush(job).getId();
        });
        when(media.isHealthy()).thenAnswer(call -> {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                var current = videos.findByIdForUpdate(video.getId()).orElseThrow();
                var newer = new AnalysisJob(current); newer.start(); newer.complete();
                jobs.saveAndFlush(newer);
                current.updateStatus(AnalysisStatus.COMPLETED);
            });
            return true;
        });
        assertError(() -> analysis.retry(video.getId()), ErrorCode.INVALID_ANALYSIS_STATE);
        assertThat(jobs.findTopByVideoIdOrderByIdDesc(video.getId()).orElseThrow().getId()).isNotEqualTo(old);
        assertThat(videos.findById(video.getId()).orElseThrow().reviewStatusOrDefault()).isEqualTo(ReviewStatus.COMPLETED);
        verifyNoInteractions(pipeline, publisher);
    }

    @ParameterizedTest @EnumSource(value = AnalysisStatus.class, names = {"FAILED", "CANCELLED"})
    void eligibleRetryResetsReviewAndCreatesNewJob(AnalysisStatus state) throws Exception {
        var video = seed();
        var old = new TransactionTemplate(manager).execute(tx -> {
            var current = videos.findByIdForUpdate(video.getId()).orElseThrow();
            current.markReviewCompleted(java.time.LocalDateTime.now());
            current.updateStatus(state);
            var job = new AnalysisJob(current);
            if (state == AnalysisStatus.FAILED) job.fail("TEST", "retryable"); else job.cancel();
            return jobs.saveAndFlush(job).getId();
        });
        var invoked = new CompletableFuture<Long>();
        doAnswer(call -> { invoked.complete(call.getArgument(0)); return null; }).when(pipeline).run(anyLong());
        var response = analysis.retry(video.getId());
        var next = invoked.get(3, TimeUnit.SECONDS);
        assertThat(next).isNotEqualTo(old);
        assertThat(response.status()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(jobs.findById(old).orElseThrow().getStatus()).isEqualTo(state);
        var current = videos.findById(video.getId()).orElseThrow();
        assertThat(current.reviewStatusOrDefault()).isEqualTo(ReviewStatus.NOT_STARTED);
        assertThat(current.getReviewedAt()).isNull();
        awaitIdle();
    }

    @Test void admissionRechecksVideoDeletedDuringHealth() {
        var video = seed();
        when(media.isHealthy()).thenAnswer(call -> { videos.deleteById(video.getId()); return true; });
        assertError(() -> analysis.startAnalysis(video.getId()), ErrorCode.VIDEO_NOT_FOUND);
        assertThat(jobs.findTopByVideoIdOrderByIdDesc(video.getId())).isEmpty();
        verifyNoInteractions(pipeline, publisher);
    }

    private void assertError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode()).isEqualTo(expected));
    }

    private void awaitIdle() throws InterruptedException {
        // Wait for capacity rather than racing the next test against task return.
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (executor.getActiveCount() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(executor.getActiveCount()).isZero();
    }
}
