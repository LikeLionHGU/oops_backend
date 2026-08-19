package com.example.oops.service;

import com.example.oops.client.AnalysisServerClient;
import com.example.oops.common.BusinessException;
import com.example.oops.common.ErrorCode;
import com.example.oops.domain.*;
import com.example.oops.dto.*;
import com.example.oops.fusion.FindingOrder;
import com.example.oops.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.oops.common.Ids;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisService {

    private final VideoService videoService;
    private final AnalysisPipeline analysisPipeline;
    private final AnalysisServerClient analysisServerClient;
    private final AnalysisJobRepository jobRepository;
    private final RiskFindingRepository findingRepository;
    private final AnalysisCoverageRepository coverageRepository;
    private final TranscriptSegmentRepository transcriptRepository;
    private final ScreenTextRepository screenTextRepository;
    private final ReviewActionRepository actionRepository;

    /**
     * 분석 실행 1회를 새로 만든다. 업로드 직후와 재시도 모두 이 메서드를 쓴다.
     * 실제 작업은 커밋 후 백그라운드에서 돈다.
     *
     * 엔티티가 아니라 videoId 를 받는다.
     * 업로드 트랜잭션에서 넘어온 Video 는 이미 detached 라서
     * 그대로 상태를 바꾸면 DB 에 반영되지 않기 때문이다.
     */
    @Transactional
    public AnalysisJob startAnalysis(Long videoId) {
        Video video = videoService.getEntity(videoId);

        boolean running = jobRepository.existsByVideoIdAndStatusIn(
                video.getId(), List.of(AnalysisStatus.PENDING, AnalysisStatus.PROCESSING));
        if (running) {
            throw new BusinessException(ErrorCode.ANALYSIS_IN_PROGRESS);
        }

        if (!analysisServerClient.isHealthy()) {
            throw new BusinessException(ErrorCode.WORKER_UNAVAILABLE,
                    "분석 서버에 연결할 수 없습니다. oops-analysis 가 실행 중인지 확인하세요.");
        }

        AnalysisJob job = jobRepository.save(new AnalysisJob(video));
        video.updateStatus(AnalysisStatus.PENDING);

        // 커밋이 끝난 뒤에 비동기 스레드를 띄운다.
        // 바로 호출하면 다른 스레드에서 아직 커밋 안 된 job 을 못 찾는 경우가 생긴다.
        Long jobId = job.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                analysisPipeline.runAsync(jobId);
            }
        });

        return job;
    }

    /**
     * 재시도. 명세 §7 — 실패하거나 취소한 작업만 다시 돌린다.
     * videoId 는 유지하고 새 jobId 를 발급한다.
     */
    @Transactional
    public AnalysisRetryResponse retry(Long videoId) {
        AnalysisJob last = jobRepository.findTopByVideoIdOrderByIdDesc(videoId).orElse(null);
        if (last != null && !last.getStatus().isRetryable()) {
            throw new BusinessException(ErrorCode.INVALID_ANALYSIS_STATE,
                    "실패했거나 취소한 분석만 다시 시도할 수 있습니다. (현재 상태: %s)"
                            .formatted(last.getStatus()));
        }
        // 후보 id 가 새로 발급되므로 검수도 처음부터다
        videoService.getEntity(videoId).resetReview();
        return AnalysisRetryResponse.from(startAnalysis(videoId));
    }

    /**
     * 취소. 명세 §7 — 대기 중이거나 진행 중인 작업만 취소한다.
     *
     * 이미 도는 스레드를 중간에 죽이지는 않는다.
     * 상태만 CANCELLED 로 바꾸고, 파이프라인이 다음 단계로 넘어갈 때 스스로 멈춘다.
     * 강제로 끊으면 반쯤 저장된 결과가 남는다.
     */
    @Transactional
    public AnalysisRetryResponse cancel(Long videoId) {
        AnalysisJob job = latestJob(videoId);
        if (!job.getStatus().isRunning()) {
            throw new BusinessException(ErrorCode.INVALID_ANALYSIS_STATE,
                    "대기 중이거나 진행 중인 분석만 취소할 수 있습니다. (현재 상태: %s)"
                            .formatted(job.getStatus()));
        }
        job.cancel();
        job.getVideo().updateStatus(AnalysisStatus.CANCELLED);
        log.info("[cancel] videoId={} jobId={} 취소", videoId, job.getJobKey());
        return AnalysisRetryResponse.from(job);
    }

    /** API 명세 3-1 */
    public VideoStatusResponse getStatus(Long videoId) {
        return VideoStatusResponse.from(latestJob(videoId));
    }

    /** 검수 리포트. 명세 §5 */
    public AnalysisReportResponse getReport(Long videoId) {
        Video video = videoService.getEntity(videoId);
        AnalysisJob job = latestJob(videoId);

        if (job.getStatus() != AnalysisStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.ANALYSIS_NOT_COMPLETED,
                    "분석이 아직 완료되지 않았습니다. (현재 상태: %s)".formatted(job.getStatus()));
        }

        List<RiskFinding> findings = findingRepository.findByVideoIdWithReferences(videoId).stream()
                .sorted(FindingOrder.byPriority())
                .toList();

        // 결정 내역을 한 번에 읽어 카드마다 붙인다
        Map<Long, ReviewActionType> actions = new HashMap<>();
        for (ReviewAction a : actionRepository.findByVideoIdOrderByIdAsc(videoId)) {
            actions.put(a.getFinding().getId(), a.getAction());
        }

        // 발언 카드에 앞뒤 줄을 붙인다. 명세 v2.1 §10 — contextBefore / contextAfter
        List<TranscriptSegment> transcript =
                transcriptRepository.findByVideoIdOrderByStartMsAsc(videoId);

        List<AnalysisCoverage> coverage = coverageRepository.findByVideoIdOrderByIdAsc(videoId);
        List<AnalysisWarningDto> warnings = coverage.stream()
                .filter(AnalysisCoverage::needsWarning)
                .map(AnalysisWarningDto::from)
                .toList();

        return new AnalysisReportResponse(
                Ids.of(video.getId()),
                job.getJobKey(),
                video.getFilename(),
                Ids.utc(job.getFinishedAt()),
                video.durationMs(),
                video.isStreamable()
                        ? "/api/v1/videos/%d/stream".formatted(video.getId()) : null,
                video.getSourceUrl(),
                video.reviewStatusOrDefault(),
                job.getStatus(),
                RiskSummary.of(findings),
                reviewSummary(findings, actions),
                toCoverage(coverage),
                warnings,
                findings.stream()
                        .map(f -> TimelineEventDto.from(f, actions.get(f.getId()),
                                lineBefore(transcript, f), lineAfter(transcript, f)))
                        .toList(),
                video.genreOrGeneral()
        );
    }

    /**
     * 검토 후보 앞뒤의 대본 줄.
     *
     * 카드만 보면 "요즘 젊은 사람들은" 이 앞뒤 없이 뚝 떨어져 있어서
     * 제작자가 실제로 어떤 흐름이었는지 판단하기 어렵다.
     * DB 컬럼을 늘리지 않고 리포트 만들 때 조회한다.
     */
    private String lineBefore(List<TranscriptSegment> transcript, RiskFinding f) {
        TranscriptSegment found = null;
        for (TranscriptSegment s : transcript) {
            if (s.getStartMs() >= f.getStartMs()) break;
            found = s;
        }
        return found == null ? null : found.getText();
    }

    private String lineAfter(List<TranscriptSegment> transcript, RiskFinding f) {
        for (TranscriptSegment s : transcript) {
            if (s.getStartMs() > f.getStartMs() && s.getStartMs() >= f.getEndMs()) {
                return s.getText();
            }
        }
        return null;
    }

    private ReviewSummaryDto reviewSummary(List<RiskFinding> findings,
                                           Map<Long, ReviewActionType> actions) {
        int confirmed = 0, edited = 0, hold = 0, notUseful = 0, decided = 0;

        for (RiskFinding f : findings) {
            ReviewActionType action = actions.get(f.getId());
            if (action == null) continue;
            decided++;
            switch (action) {
                case CONFIRMED -> confirmed++;
                case EDITED -> edited++;
                case HOLD -> hold++;
                case NOT_USEFUL -> notUseful++;
            }
        }
        return new ReviewSummaryDto(decided, findings.size() - decided,
                confirmed, edited, hold, notUseful);
    }

    /**
     * 무엇을 분석했는지. 명세 §5.
     *
     * 실패·건너뜀은 분석하지 못한 것으로 본다.
     * 왜 못 했는지는 warnings 가 설명한다.
     */
    private CoverageDto toCoverage(List<AnalysisCoverage> coverage) {
        // 화면 글자를 봤는지는 **어느 분석기가 봤든** true 다.
        //
        // 화면 글자 표현 검토(screen-text-review)를 껐기 때문에
        // SCREEN_TEXT_REVIEW 만 보면 항상 false 가 된다.
        // 그런데 이름·수치 확인(entity-check)이 화면 글자를 읽고 있으므로
        // 실제로는 본 것이다. 여기서 false 를 주면 사용자는
        // "화면은 아예 안 봤구나" 로 읽는다. 그건 사실이 아니다.
        boolean screenTextRead = analyzed(coverage, CoverageStep.OCR)
                && (analyzed(coverage, CoverageStep.SCREEN_TEXT_REVIEW)
                    || analyzed(coverage, CoverageStep.FACT_ENTITY));

        return new CoverageDto(
                analyzed(coverage, CoverageStep.STT) && analyzed(coverage, CoverageStep.SPEECH_REVIEW),
                screenTextRead,
                analyzed(coverage, CoverageStep.VISUAL));
    }

    private boolean analyzed(List<AnalysisCoverage> coverage, CoverageStep step) {
        return coverage.stream()
                .filter(c -> c.getStep() == step)
                .findFirst()
                .map(c -> c.getStatus() == AnalyzerStatus.SUCCESS)
                .orElse(false);
    }

    /** STT 대본 원문 (디버깅·대본 패널용) */
    public List<TranscriptLineDto> getTranscript(Long videoId) {
        return transcriptRepository.findByVideoIdOrderByStartMsAsc(videoId).stream()
                .map(TranscriptLineDto::from)
                .toList();
    }

    /** OCR 화면 자막 원문 */
    public List<TranscriptLineDto> getScreenTexts(Long videoId) {
        return screenTextRepository.findByVideoIdOrderByStartMsAsc(videoId).stream()
                .map(TranscriptLineDto::from)
                .toList();
    }

    private AnalysisJob latestJob(Long videoId) {
        videoService.getEntity(videoId); // 영상 존재 확인 → VIDEO_NOT_FOUND
        return jobRepository.findTopByVideoIdOrderByIdDesc(videoId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_ANALYSIS_STATE,
                        "아직 분석이 시작되지 않았습니다."));
    }
}
