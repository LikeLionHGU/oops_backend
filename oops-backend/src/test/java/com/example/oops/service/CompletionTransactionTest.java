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
@SpringBootTest
@ActiveProfiles("test")
class CompletionTransactionTest {
    @Autowired JobProgressService progress;
    @Autowired VideoRepository videos;
    @Autowired AnalysisJobRepository jobs;
    @Autowired AnalysisReportRepository reports;
    @Autowired PlatformTransactionManager manager;
    @Autowired AnalysisPipeline pipeline;
    @Autowired AnalysisService analysis;
    @Autowired ExpressionOccurrenceRepository expressions;
    @Autowired RiskFindingRepository findings;
    @Autowired VideoFrameRepository frames;
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
        when(transcript.extractAndSave(any())).thenAnswer(call -> java.util.List.of(
                new TranscriptSegment(call.getArgument(0), 1000, 3000, "허버허버")));
        when(screens.extractAndSave(any())).thenReturn(java.util.List.of());
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
        new TransactionTemplate(manager).executeWithoutResult(tx -> target.runAsync(id));
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
        var target=preparePipeline();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            target.runAsync(id);
            tx.setRollbackOnly(); // Simulates failure at the final result commit boundary.
        });
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            var job=jobs.findById(id).orElseThrow();
            assertThat(job.getStatus()).isEqualTo(AnalysisStatus.FAILED);
            assertThat(job.getVideo().getStatus()).isEqualTo(AnalysisStatus.FAILED);
            assertThat(expressions.findByVideoIdOrderByStartMsAscStartOffsetAsc(job.getVideo().getId())).isEmpty();
            assertThat(reports.findByVideoId(job.getVideo().getId())).isEmpty();
        });
    }
    @Test void removesOldFindingReferencesBeforeOcrFrameReplacement() throws Exception {
        Long id=seedPipeline();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            var video=jobs.findById(id).orElseThrow().getVideo();
            var frame=frames.save(new VideoFrame(video, 1000, "frames/test-old.jpg", "image/jpeg"));
            findings.saveAndFlush(RiskFinding.builder().video(video).eventType(TimelineEventType.CAPTION)
                    .category(RiskCategory.PRIVACY).source(EvidenceSource.VISION).score(0.85)
                    .startMs(1000).endMs(2000).captionText("test").reason("test").frame(frame).build());
        });
        var target=preparePipeline();
        when(screens.extractAndSave(any())).thenAnswer(call -> {
            Video video=call.getArgument(0);
            assertThat(findings.findByVideoId(video.getId())).isEmpty();
            frames.deleteByVideoId(video.getId()); frames.flush();
            return java.util.List.of();
        });
        new TransactionTemplate(manager).executeWithoutResult(tx -> target.runAsync(id));
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
