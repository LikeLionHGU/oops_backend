package com.example.oops.service;

import com.example.oops.domain.*;
import com.example.oops.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses a separate in-memory DB; no media/model calls. */
@SpringBootTest(properties = {"spring.datasource.hikari.maximum-pool-size=1", "spring.datasource.hikari.minimum-idle=0"})
@ActiveProfiles("test")
class CompletionTransactionTest {
    @Autowired JobProgressService progress;
    @Autowired VideoRepository videos;
    @Autowired AnalysisJobRepository jobs;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean AnalysisReportRepository reports;
    @Autowired PlatformTransactionManager manager;
    @Autowired AnalysisPipeline pipeline;
    @Autowired AnalysisService analysis;
    @Autowired RiskFindingRepository findings;
    @Autowired VideoFrameRepository frames;
    @Autowired TranscriptSegmentRepository segments;
    @Autowired ScreenTextRepository screenRows;
    @Autowired AnalysisResultWriter writer;
    @Autowired javax.sql.DataSource dataSource;
    @MockitoBean ProgressPublisher publisher;
    @MockitoBean com.example.oops.transcript.TranscriptService transcript;
    @MockitoBean com.example.oops.client.OpenAiClient ai;
    @MockitoBean com.example.oops.client.AnalysisServerClient media;
    @MockitoBean com.example.oops.fusion.FindingFusionService fusion;

    @Test void speechOnlyReportPersistsExplicitScreenOptOutInsteadOfOcrFailure() throws Exception {
        Long id = seedPipeline();
        preparePipeline().run(id);
        Long videoId = new TransactionTemplate(manager).execute(tx -> jobs.findById(id).orElseThrow().getVideo().getId());
        var report = analysis.getReport(videoId);
        assertThat(report.coverage().screenTextAnalyzed()).isFalse();
        assertThat(report.warnings()).extracting(com.example.oops.dto.AnalysisWarningDto::code)
                .contains("OCR_NOT_ENABLED", "SCREEN_TEXT_REVIEW_NOT_ENABLED").doesNotContain("OCR_UNAVAILABLE");
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
    }

    private Long seed() {
        return new TransactionTemplate(manager).execute(tx -> {
            var video=videos.save(Video.builder().sourceType(SourceType.UPLOAD).filename("transaction-test.mp4").build());
            var job=new AnalysisJob(video); job.start(); video.updateStatus(AnalysisStatus.PROCESSING);
            return jobs.saveAndFlush(job).getId();
        });
    }
    @Test void validatedFlowMetadataSurvivesDatabaseReloadAndLegacyRowsRemainReadable() {
        Long jobId = seed();
        Long findingId = new TransactionTemplate(manager).execute(tx -> {
            var video = jobs.findById(jobId).orElseThrow().getVideo();
            var finding = RiskFinding.builder().video(video).eventType(TimelineEventType.SPEECH)
                    .category(RiskCategory.BELITTLEMENT).source(EvidenceSource.SUBTITLE)
                    .score(.7).startMs(1000).endMs(2000).text("실제 원문").reason("검토 근거").build();
            finding.recordValidatedSupports(java.util.List.of(new FindingSupport("stt-test", "REGION",
                    java.util.List.of(new FindingSupport.Quote("stt-test", TimelineEventType.SPEECH, 1000, 2000, "실제 원문", "PRIMARY")))));
            finding.recordOccurrenceCount(1); finding.applyFusion(500, false, 2);
            return findings.saveAndFlush(finding).getId();
        });
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            var finding = findings.findById(findingId).orElseThrow();
            var dto = com.example.oops.dto.TimelineEventDto.from(finding, null, null, null);
            assertThat(dto.occurrences()).isOne(); assertThat(dto.supportCount()).isEqualTo(2);
            assertThat(dto.relatedEvidence()).singleElement().extracting(FindingSupport.Quote::quote).isEqualTo("실제 원문");
            var legacy = RiskFinding.builder().video(finding.getVideo()).eventType(TimelineEventType.SPEECH)
                    .category(RiskCategory.BELITTLEMENT).source(EvidenceSource.SUBTITLE)
                    .score(.7).startMs(1000).endMs(2000).text("과거 원문").build();
            legacy.applyFusion(500, false, 3);
            findings.saveAndFlush(legacy);
            assertThat(com.example.oops.dto.TimelineEventDto.from(legacy, null, null, null).occurrences()).isEqualTo(3);
            assertThat(legacy.validatedSupports()).isEmpty();
        });
    }
    @Test void completionAndReportRollbackTogetherAndNeverPublishCompletion() {
        Long id=seed(); reset(publisher);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            var job=jobs.findById(id).orElseThrow();
            reports.save(new AnalysisReport(job.getVideo(), 0, 0, "test"));
            assertThat(progress.complete(id)).isTrue();
            verifyNoInteractions(publisher);
            tx.setRollbackOnly();
        });
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            var job=jobs.findById(id).orElseThrow();
            assertThat(job.getStatus()).isEqualTo(AnalysisStatus.PROCESSING);
            assertThat(job.getVideo().getStatus()).isEqualTo(AnalysisStatus.PROCESSING);
            assertThat(reports.findByVideoId(job.getVideo().getId())).isEmpty();
        });
        verifyNoInteractions(publisher);
    }
    @Test void successfulCompletionPublishesOnlyAfterResultCommit() {
        Long id=seed(); reset(publisher);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            var job=jobs.findById(id).orElseThrow();
            reports.save(new AnalysisReport(job.getVideo(), 0, 0, "test"));
            assertThat(progress.complete(id)).isTrue();
            verifyNoInteractions(publisher);
        });
        verify(publisher).publish(any());
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            var job=jobs.findById(id).orElseThrow();
            assertThat(job.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
            assertThat(job.getVideo().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
            assertThat(reports.findByVideoId(job.getVideo().getId())).isPresent();
        });
    }
    @Test void cancelledJobCannotBeCompleted() {
        Long id=seed(); reset(publisher);
        new TransactionTemplate(manager).executeWithoutResult(tx -> jobs.findById(id).orElseThrow().cancel());
        new TransactionTemplate(manager).executeWithoutResult(tx -> assertThat(progress.complete(id)).isFalse());
        verifyNoInteractions(publisher);
    }
    private Long seedPipeline() {
        return new TransactionTemplate(manager).execute(tx -> {
            var video=videos.save(Video.builder().sourceType(SourceType.UPLOAD)
                    .genre(ContentGenre.GENERAL).filename("pipeline-test.mp4").build());
            return jobs.saveAndFlush(new AnalysisJob(video)).getId();
        });
    }
    private AnalysisPipeline preparePipeline() throws Exception {
        when(transcript.extract(any())).thenAnswer(call -> java.util.List.of(
                new TranscriptSegment(call.getArgument(0), 1000, 3000, "허버허버")));
        when(media.lastFailureDetail()).thenReturn(java.util.Optional.empty());
        when(fusion.fuse(anyList())).thenReturn(java.util.List.of());
        var usage=mock(com.example.oops.client.OpenAiClient.TokenUsage.class);
        when(usage.pricing()).thenReturn(mock(com.example.oops.config.OpenAiProperties.Pricing.class));
        when(usage.isEmpty()).thenReturn(true);
        when(usage.model()).thenReturn("offline-test");
        when(ai.videoUsage()).thenReturn(usage);
        return org.springframework.test.util.AopTestUtils.getTargetObject(pipeline);
    }
    @Test void fullPipelineRollbackMarksJobFailedAndKeepsPreviousRows() throws Exception {
        Long id=seedPipeline();
        Long videoId=new TransactionTemplate(manager).execute(tx -> {
            var video=jobs.findById(id).orElseThrow().getVideo();
            segments.save(new TranscriptSegment(video, 0, 1000, "이전 원문"));
            return video.getId();
        });
        var target=preparePipeline();
        doThrow(new IllegalStateException("simulated result write failure")).when(reports).save(any());
        target.run(id);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            var job=jobs.findById(id).orElseThrow();
            assertThat(job.getStatus()).isEqualTo(AnalysisStatus.FAILED);
            assertThat(job.getVideo().getStatus()).isEqualTo(AnalysisStatus.FAILED);
            assertThat(reports.findByVideoId(job.getVideo().getId())).isEmpty();
            assertThat(segments.findCurrentByVideoId(videoId)).extracting(TranscriptSegment::getText).containsExactly("이전 원문");
        });
        verify(publisher, never()).publish(argThat(j -> j.getStatus() == AnalysisStatus.COMPLETED));
    }
    @Test void databaseFlushFailureRollsBackReplacedRawRowsReportAndCompletion() {
        Long id=seed();
        var video=new TransactionTemplate(manager).execute(tx -> {
            var v=jobs.findById(id).orElseThrow().getVideo();
            segments.save(new TranscriptSegment(v, 0, 1000, "이전 원문"));
            reports.save(new AnalysisReport(v, 0, 0, "이전 리포트"));
            return v;
        });
        reset(publisher);
        var result=new AnalysisResultWriter.Result(video, ContentGenre.GENERAL,
                java.util.List.of(new TranscriptSegment(video, 1000, 3000, "허버허버")),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), 0, "x".repeat(3000),
                "offline");
        assertThatThrownBy(() -> writer.save(id, result)).isInstanceOf(RuntimeException.class);
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.PROCESSING);
        assertThat(reports.findByVideoId(video.getId()).orElseThrow().getSummary()).isEqualTo("이전 리포트");
        assertThat(segments.findCurrentByVideoId(video.getId())).extracting(TranscriptSegment::getText).containsExactly("이전 원문");
        verifyNoInteractions(publisher);
    }
    @Test void externalExtractionHasNoTransactionOrCheckedOutConnection() throws Exception {
        Long id=seedPipeline();
        var target=preparePipeline();
        when(transcript.extract(any())).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(((com.zaxxer.hikari.HikariDataSource)dataSource).getHikariPoolMXBean().getActiveConnections()).isZero();
            return java.util.List.of(new TranscriptSegment(call.getArgument(0), 1000, 3000, "허버허버"));
        });
        target.run(id);
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
    }
    @Test void failedDuringExtractionDoesNotSaveResults() throws Exception {
        Long id=seedPipeline(); var target=preparePipeline();
        when(transcript.extract(any())).thenAnswer(call -> {
            assertThat(progress.fail(id, "TEST_STOP", "fixture")).isTrue();
            return java.util.List.of(new TranscriptSegment(call.getArgument(0), 1000, 3000, "원문"));
        });
        target.run(id);
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.FAILED);
        Long videoId=new TransactionTemplate(manager).execute(tx -> jobs.findById(id).orElseThrow().getVideo().getId());
        assertThat(reports.findByVideoId(videoId)).isEmpty();
        assertThat(segments.findCurrentByVideoId(videoId)).isEmpty();
    }
    @Test void cancelledThenRetriedRunCannotReplacePreviousRows() throws Exception {
        Long id=seedPipeline();
        var target=preparePipeline();
        Long videoId=new TransactionTemplate(manager).execute(tx -> {
            var video=jobs.findById(id).orElseThrow().getVideo();
            segments.save(new TranscriptSegment(video, 0, 1000, "이전 원문"));
            return video.getId();
        });
        when(transcript.extract(any())).thenAnswer(call -> {
            analysis.cancel(videoId);
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                var video=videos.findByIdForUpdate(videoId).orElseThrow();
                jobs.saveAndFlush(new AnalysisJob(video));
                video.updateStatus(AnalysisStatus.PENDING);
            });
            return java.util.List.of(new TranscriptSegment(call.getArgument(0), 1000, 3000, "새 원문"));
        });
        target.run(id);
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(segments.findCurrentByVideoId(videoId)).extracting(TranscriptSegment::getText).containsExactly("이전 원문");
        assertThat(videos.findById(videoId).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.PENDING);
    }
    @Test void writerRejectsSupersededAndCancelledSnapshotBeforeDeletingAnything() {
        Long id=seed();
        var video=new TransactionTemplate(manager).execute(tx -> jobs.findById(id).orElseThrow().getVideo());
        var result=new AnalysisResultWriter.Result(video, ContentGenre.GENERAL, java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), 0, "unused", "offline");
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            jobs.findById(id).orElseThrow().cancel();
            segments.save(new TranscriptSegment(video, 0, 1000, "보존 원문"));
        });
        assertThat(writer.save(id, result)).isFalse();
        new TransactionTemplate(manager).executeWithoutResult(tx -> jobs.saveAndFlush(new AnalysisJob(video)));
        assertThat(writer.save(id, result)).isFalse();
        assertThat(segments.findCurrentByVideoId(video.getId())).extracting(TranscriptSegment::getText).containsExactly("보존 원문");
    }
    @Test void twoAnalysesCanWaitOnMediaWithOnlyOneDatabaseConnection() throws Exception {
        Long first=seedPipeline(), second=seedPipeline();
        var target=preparePipeline();
        var entered=new java.util.concurrent.CountDownLatch(2);
        var release=new java.util.concurrent.CountDownLatch(1);
        when(transcript.extract(any())).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();
            assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return java.util.List.of(new TranscriptSegment(call.getArgument(0), 1000, 3000, "허버허버"));
        });
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a=executor.submit(() -> target.run(first));
            var b=executor.submit(() -> target.run(second));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(((com.zaxxer.hikari.HikariDataSource)dataSource).getHikariPoolMXBean().getActiveConnections()).isZero();
            // A status query is still possible while both external calls are blocked.
            assertThat(jobs.findById(first).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.PROCESSING);
            release.countDown();
            a.get(5, java.util.concurrent.TimeUnit.SECONDS);
            b.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(jobs.findById(first).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
            assertThat(jobs.findById(second).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void cancellationUpdatesJobAndVideoAndCannotCancelCompletedJob() {
        Long id=seed();
        Long videoId=new TransactionTemplate(manager).execute(tx -> jobs.findById(id).orElseThrow().getVideo().getId());
        analysis.cancel(videoId);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            var job=jobs.findById(id).orElseThrow();
            assertThat(job.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
            assertThat(job.getVideo().getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        });
        Long completeId=seed();
        progress.complete(completeId);
        Long completedVideo=new TransactionTemplate(manager).execute(tx -> jobs.findById(completeId).orElseThrow().getVideo().getId());
        assertThatThrownBy(() -> analysis.cancel(completedVideo)).isInstanceOf(com.example.oops.common.BusinessException.class);
    }
}
