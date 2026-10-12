package com.example.oops.service;

import com.example.oops.analyzer.ReviewDiagnostics;
import com.example.oops.domain.*;
import com.example.oops.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/** The sole result commit boundary; no media or model calls belong here. */
@Service
@RequiredArgsConstructor
public class AnalysisResultWriter {
    private final VideoRepository videos;
    private final AnalysisJobRepository jobs;
    private final TranscriptSegmentRepository transcripts;
    private final ScreenTextRepository screenTexts;
    private final VideoFrameRepository frames;
    private final ExpressionOccurrenceRepository expressions;
    private final ReviewActionRepository actions;
    private final ReviewReferenceRepository references;
    private final RiskFindingRepository findings;
    private final AnalysisCoverageRepository coverage;
    private final AnalysisReportRepository reports;
    private final ExpressionService expressionService;
    private final JobProgressService progress;
    private final ReviewDiagnosticsStore diagnostics;

    @Transactional
    public boolean save(Long jobId, Result result) {
        var video = videos.findByIdForUpdate(result.video().getId()).orElse(null);
        if (video == null) return false;
        // Video -> latest job matches start/retry/cancel. A cancelled or superseded
        // run must never publish, even if its external calls have just returned.
        var job = jobs.findFirstByVideoIdOrderByIdDesc(video.getId()).orElse(null);
        if (job == null || !job.getId().equals(jobId) || job.getStatus() != AnalysisStatus.PROCESSING) return false;

        actions.deleteByVideoId(video.getId());
        references.deleteByVideoId(video.getId());
        findings.deleteByVideoId(video.getId());
        findings.flush();
        expressions.deleteByVideoId(video.getId());
        expressions.flush();
        transcripts.deleteByVideoId(video.getId());
        screenTexts.deleteByVideoId(video.getId());
        screenTexts.flush();
        frames.deleteByVideoId(video.getId());
        frames.flush();

        // These are the same objects used by the analyzers: keep frame associations
        // and index-based evidence IDs rather than reconstructing the judged input.
        transcripts.saveAll(result.transcript());
        frames.saveAll(result.screenTexts().stream().map(ScreenText::getFrame)
                .filter(java.util.Objects::nonNull).distinct().toList());
        screenTexts.saveAll(result.screenTexts());
        expressionService.replaceSnapshot(video, result.transcript());
        coverage.deleteByVideoId(video.getId());
        coverage.saveAll(result.coverage());
        findings.saveAll(result.findings());
        reports.findByVideoId(video.getId()).ifPresentOrElse(
                r -> r.update(result.riskScore(), result.findings().size(), result.summary()),
                () -> reports.save(new AnalysisReport(video, result.riskScore(), result.findings().size(), result.summary())));
        video.updateMetadata(result.video().getTitle(), result.video().getChannelName(), result.video().getDurationSec());
        if (video.getGenre() == null) video.assignGenre(result.genre());
        if (!progress.complete(jobId)) throw new IllegalStateException("Analysis no longer processing");
        diagnostics.recordAfterCommit(video.getId(), jobId, result.model(), result.diagnostics());
        return true;
    }

    public record Result(Video video, ContentGenre genre, List<TranscriptSegment> transcript,
                         List<ScreenText> screenTexts, Collection<AnalysisCoverage> coverage,
                         List<RiskFinding> findings, int riskScore, String summary,
                         String model, List<ReviewDiagnostics> diagnostics) {
        public Result {
            transcript = List.copyOf(transcript);
            screenTexts = List.copyOf(screenTexts);
            coverage = List.copyOf(coverage);
            findings = List.copyOf(findings);
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
