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
    @Autowired ExpressionOccurrenceRepository expressions;
    @Autowired RiskFindingRepository findings;
    @Autowired VideoFrameRepository frames;
    @Autowired TranscriptSegmentRepository segments;
    @Autowired ScreenTextRepository screenRows;
    @Autowired AnalysisResultWriter writer;
    @Autowired javax.sql.DataSource dataSource;
    @MockitoBean ProgressPublisher publisher;
    @MockitoBean com.example.oops.transcript.TranscriptService transcript;
    @MockitoBean com.example.oops.screentext.ScreenTextService screens;
    @MockitoBean com.example.oops.client.OpenAiClient ai;
    @MockitoBean com.example.oops.client.AnalysisServerClient media;
    @MockitoBean com.example.oops.fusion.FindingFusionService fusion;

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
        when(screens.extract(any(), anyLong())).thenReturn(java.util.List.of());
        when(media.lastFailureDetail()).thenReturn(java.util.Optional.empty());
        when(fusion.fuse(anyList())).thenReturn(java.util.List.of());
        var usage=mock(com.example.oops.client.OpenAiClient.TokenUsage.class);
        when(usage.pricing()).thenReturn(mock(com.example.oops.config.OpenAiProperties.Pricing.class));
        when(usage.isEmpty()).thenReturn(true);
        when(usage.model()).thenReturn("offline-test");
        when(ai.videoUsage()).thenReturn(usage);
        return org.springframework.test.util.AopTestUtils.getTargetObject(pipeline);
    }
    @Test void fullPipelinePersistsOptionalExpressionsSeparatelyFromContextCards() throws Exception {
        Long id=seedPipeline();
        var target=preparePipeline();
        target.run(id);
        Long videoId=new TransactionTemplate(manager).execute(tx -> jobs.findById(id).orElseThrow().getVideo().getId());
        var report=analysis.getReport(videoId);
        assertThat(report.expressionDetectionStatus()).isEqualTo("SUCCESS");
        assertThat(report.expressionOccurrences()).hasSize(1);
        assertThat(report.expressionOccurrences().get(0).status()).isEqualTo("EXPRESSION_DETECTED");
        assertThat(report.events()).isEmpty();
        assertThat(report.summary().total()).isZero();
    }
    @Test void fullPipelineRollbackMarksJobFailedAndDoesNotLeaveExpressionRows() throws Exception {
        Long id=seedPipeline();
        Long videoId=new TransactionTemplate(manager).execute(tx -> {
            var video=jobs.findById(id).orElseThrow().getVideo();
            segments.save(new TranscriptSegment(video, 0, 1000, "이전 원문"));
            return video.getId();
        });
        var target=preparePipeline();
        doThrow(new IllegalStateException("simulated result write failure")).when(reports).save(any());
        target.run(id);
        verify(screens).releaseFrames(any(), eq(id));
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            var job=jobs.findById(id).orElseThrow();
            assertThat(job.getStatus()).isEqualTo(AnalysisStatus.FAILED);
            assertThat(job.getVideo().getStatus()).isEqualTo(AnalysisStatus.FAILED);
            assertThat(expressions.findByVideoIdOrderByStartMsAscStartOffsetAsc(job.getVideo().getId())).isEmpty();
            assertThat(reports.findByVideoId(job.getVideo().getId())).isEmpty();
            assertThat(segments.findByVideoIdOrderByStartMsAsc(videoId)).extracting(TranscriptSegment::getText).containsExactly("이전 원문");
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
                "offline", java.util.List.of());
        assertThatThrownBy(() -> writer.save(id, result)).isInstanceOf(RuntimeException.class);
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.PROCESSING);
        assertThat(reports.findByVideoId(video.getId()).orElseThrow().getSummary()).isEqualTo("이전 리포트");
        assertThat(segments.findByVideoIdOrderByStartMsAsc(video.getId())).extracting(TranscriptSegment::getText).containsExactly("이전 원문");
        assertThat(expressions.findByVideoIdOrderByStartMsAscStartOffsetAsc(video.getId())).isEmpty();
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
    @Test void failedDuringExtractionDoesNotLaunchOcrOrSaveResults() throws Exception {
        Long id=seedPipeline(); var target=preparePipeline();
        when(transcript.extract(any())).thenAnswer(call -> {
            assertThat(progress.fail(id, "TEST_STOP", "fixture")).isTrue();
            return java.util.List.of(new TranscriptSegment(call.getArgument(0), 1000, 3000, "원문"));
        });
        target.run(id);
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.FAILED);
        verify(screens, never()).extract(any(), anyLong());
        Long videoId=new TransactionTemplate(manager).execute(tx -> jobs.findById(id).orElseThrow().getVideo().getId());
        assertThat(reports.findByVideoId(videoId)).isEmpty();
        assertThat(segments.findByVideoIdOrderByStartMsAsc(videoId)).isEmpty();
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
        assertThat(segments.findByVideoIdOrderByStartMsAsc(videoId)).extracting(TranscriptSegment::getText).containsExactly("이전 원문");
        assertThat(videos.findById(videoId).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.PENDING);
    }
    @Test void writerRejectsSupersededAndCancelledSnapshotBeforeDeletingAnything() {
        Long id=seed();
        var video=new TransactionTemplate(manager).execute(tx -> jobs.findById(id).orElseThrow().getVideo());
        var result=new AnalysisResultWriter.Result(video, ContentGenre.GENERAL, java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), 0, "unused", "offline", java.util.List.of());
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            jobs.findById(id).orElseThrow().cancel();
            segments.save(new TranscriptSegment(video, 0, 1000, "보존 원문"));
        });
        assertThat(writer.save(id, result)).isFalse();
        new TransactionTemplate(manager).executeWithoutResult(tx -> jobs.saveAndFlush(new AnalysisJob(video)));
        assertThat(writer.save(id, result)).isFalse();
        assertThat(segments.findByVideoIdOrderByStartMsAsc(video.getId())).extracting(TranscriptSegment::getText).containsExactly("보존 원문");
    }
    @Test void savesUnsavedFrameReferencesAndPreservesSnapshotEvidenceIds() throws Exception {
        Long id=seedPipeline();
        var target=preparePipeline();
        var retained=new java.util.concurrent.atomic.AtomicReference<ScreenText>();
        when(screens.extract(any(), anyLong())).thenAnswer(call -> {
            Video video=call.getArgument(0);
            var frame=new VideoFrame(video, 1000, "frames/isolated/jobs/test.jpg", "image/jpeg");
            var text=new ScreenText(video, 1000, 3000, "화면 원문", .9, frame);
            retained.set(text);
            return java.util.List.of(text);
        });
        when(fusion.fuse(anyList())).thenAnswer(call -> {
            var text=retained.get();
            var finding=RiskFinding.builder().video(text.getVideo()).frame(text.getFrame())
                    .eventType(TimelineEventType.CAPTION).category(RiskCategory.PRIVACY).source(EvidenceSource.VISION)
                    .score(.7).startMs(1000).endMs(3000).captionText(text.getText()).reason("offline test").build();
            finding.recordValidatedSupports(java.util.List.of(new FindingSupport("ocr-index-0", "PRIVACY",
                    java.util.List.of(new FindingSupport.Quote("ocr-index-0", TimelineEventType.CAPTION,
                            1000, 3000, "화면 원문", "PRIMARY")))));
            return java.util.List.of(finding);
        });
        target.run(id);
        Long videoId=new TransactionTemplate(manager).execute(tx -> jobs.findById(id).orElseThrow().getVideo().getId());
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            var rows=screenRows.findByVideoIdOrderByStartMsAsc(videoId);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getFrame().getId()).isNotNull();
            assertThat(rows.get(0).getFrame().getStorageKey()).isEqualTo("frames/isolated/jobs/test.jpg");
            var cards=findings.findByVideoId(videoId);
            assertThat(cards).hasSize(1);
            assertThat(cards.get(0).getFrame().getId()).isEqualTo(rows.get(0).getFrame().getId());
            assertThat(cards.get(0).validatedSupports().get(0).quotes().get(0).segmentId()).isEqualTo("ocr-index-0");
            assertThat(expressions.findByVideoIdOrderByStartMsAscStartOffsetAsc(videoId)).singleElement()
                    .extracting(ExpressionOccurrence::getSegmentId).isEqualTo("stt-index-0");
        });
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
    @Test void retainsOldFindingsDuringExtractionAndReplacesFramesAtCommit() throws Exception {
        Long id=seedPipeline();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            var video=jobs.findById(id).orElseThrow().getVideo();
            var frame=frames.save(new VideoFrame(video, 1000, "frames/test-old.jpg", "image/jpeg"));
            findings.saveAndFlush(RiskFinding.builder().video(video).eventType(TimelineEventType.CAPTION)
                    .category(RiskCategory.PRIVACY).source(EvidenceSource.VISION).score(0.85)
                    .startMs(1000).endMs(2000).captionText("test").reason("test").frame(frame).build());
        });
        var target=preparePipeline();
        when(screens.extract(any(), anyLong())).thenAnswer(call -> {
            Video video=call.getArgument(0);
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(findings.findByVideoId(video.getId())).hasSize(1);
            return java.util.List.of();
        });
        target.run(id);
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
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
