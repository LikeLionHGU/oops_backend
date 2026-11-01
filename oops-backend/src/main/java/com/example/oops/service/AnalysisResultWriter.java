package com.example.oops.service;

import com.example.oops.domain.*;
import com.example.oops.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * 결과를 저장하는 유일한 지점. 여기서는 미디어·모델 호출을 하지 않는다.
 *
 * redesign-v3 8단계 — 실행 단위 저장:
 * 재분석해도 이전 실행의 카드·참고 자료·전사·수행 기록·검수 기록을 지우지 않는다.
 * 새 행에는 run_id(= 이 분석 작업 ID)를 붙이고, 커밋 순간에 Video.currentRunId 를 새 실행으로 바꾼다.
 * 조회는 모두 현재 실행만 읽으므로, 실패·취소된 실행은 화면에 섞이지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AnalysisResultWriter {
    private final VideoRepository videos;
    private final AnalysisJobRepository jobs;
    private final TranscriptSegmentRepository transcripts;
    private final RiskFindingRepository findings;
    private final AnalysisCoverageRepository coverage;
    private final AnalysisReportRepository reports;
    private final AnalysisRunRepository runs;
    private final AnalysisTraceRepository traces;
    private final JobProgressService progress;

    @Transactional
    public boolean save(Long jobId, Result result) {
        var video = videos.findByIdForUpdate(result.video().getId()).orElse(null);
        if (video == null) return false;
        // Video -> latest job matches start/retry/cancel. A cancelled or superseded
        // run must never publish, even if its external calls have just returned.
        var job = jobs.findFirstByVideoIdOrderByIdDesc(video.getId()).orElse(null);
        if (job == null || !job.getId().equals(jobId) || job.getStatus() != AnalysisStatus.PROCESSING) return false;

        // 분석기가 쓴 객체를 그대로 저장한다. 구간 ID 가 판단에 쓴 입력과 어긋나지 않게 하기 위해서다.
        result.transcript().forEach(t -> t.assignRun(jobId));
        result.coverage().forEach(c -> c.assignRun(jobId));
        result.findings().forEach(f -> f.assignRun(jobId));
        transcripts.saveAll(result.transcript());
        coverage.saveAll(result.coverage());
        findings.saveAll(result.findings());
        runs.save(new AnalysisRun(video, jobId, result.criteriaVersion(), result.model(), result.riskScore(),
                result.findings().size(), result.summary(), result.transcriptReused()));
        traces.saveAll(result.traces().stream()
                .map(t -> new AnalysisTrace(video, jobId, t.analyzer(), t.json())).toList());

        // 화면용 요약은 영상당 하나(현재 실행). 실행별 값은 analysis_run 에 남는다.
        reports.findByVideoId(video.getId()).ifPresentOrElse(
                r -> r.update(result.riskScore(), result.findings().size(), result.summary()),
                () -> reports.save(new AnalysisReport(video, result.riskScore(), result.findings().size(), result.summary())));
        video.updateMetadata(result.video().getTitle(), result.video().getChannelName(), result.video().getDurationSec());
        if (video.getGenre() == null) video.assignGenre(result.genre());
        video.switchToRun(jobId);
        if (!progress.complete(jobId)) throw new IllegalStateException("Analysis no longer processing");
        return true;
    }

    public record TraceEntry(String analyzer, String json) {}

    public record Result(Video video, ContentGenre genre, List<TranscriptSegment> transcript,
                         List<ScreenText> screenTexts, Collection<AnalysisCoverage> coverage,
                         List<RiskFinding> findings, int riskScore, String summary,
                         String model, String criteriaVersion, boolean transcriptReused,
                         List<TraceEntry> traces) {
        public Result {
            transcript = List.copyOf(transcript);
            screenTexts = List.copyOf(screenTexts);
            coverage = List.copyOf(coverage);
            findings = List.copyOf(findings);
            traces = traces == null ? List.of() : List.copyOf(traces);
        }

        public Result(Video video, ContentGenre genre, List<TranscriptSegment> transcript,
                      List<ScreenText> screenTexts, Collection<AnalysisCoverage> coverage,
                      List<RiskFinding> findings, int riskScore, String summary, String model) {
            this(video, genre, transcript, screenTexts, coverage, findings, riskScore, summary, model, null, false, List.of());
        }
    }
}
