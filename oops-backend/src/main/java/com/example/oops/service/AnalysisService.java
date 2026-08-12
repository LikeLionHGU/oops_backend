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

import java.util.List;

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
    private final TranscriptSegmentRepository transcriptRepository;
    private final ScreenTextRepository screenTextRepository;

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

    /** API 명세 9-1. videoId 는 유지하고 새 jobId 를 발급한다. */
    @Transactional
    public AnalysisRetryResponse retry(Long videoId) {
        return AnalysisRetryResponse.from(startAnalysis(videoId));
    }

    /** API 명세 3-1 */
    public VideoStatusResponse getStatus(Long videoId) {
        return VideoStatusResponse.from(latestJob(videoId));
    }

    /** API 명세 5-1 */
    public AnalysisReportResponse getReport(Long videoId) {
        Video video = videoService.getEntity(videoId);
        AnalysisJob job = latestJob(videoId);

        if (job.getStatus() != AnalysisStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.ANALYSIS_NOT_COMPLETED,
                    "분석이 아직 완료되지 않았습니다. (현재 상태: %s)".formatted(job.getStatus()));
        }

        List<RiskFinding> findings = findingRepository.findByVideoId(videoId).stream()
                .sorted(FindingOrder.byPriority())
                .toList();

        AdSuitability adSuitability = predictAdSuitability(findings);

        return new AnalysisReportResponse(
                video.getId(),
                job.getJobKey(),
                job.getStatus(),
                video.genreOrGeneral(),
                adSuitability,
                adSuitability.getNote(),
                RiskSummary.of(findings),
                findings.stream().map(TimelineEventDto::from).toList()
        );
    }

    /**
     * 영상 전체의 광고 적합성.
     * 유튜브도 가장 심한 구간을 기준으로 등급을 매기므로 최악값을 따른다.
     */
    private AdSuitability predictAdSuitability(List<RiskFinding> findings) {
        AdSuitability worst = AdSuitability.MONETIZED;
        for (RiskFinding f : findings) {
            if (f.getCategory() == RiskCategory.AD_DEMONETIZED) {
                worst = worst.worse(AdSuitability.DEMONETIZED);
            } else if (f.getCategory() == RiskCategory.AD_LIMITED) {
                worst = worst.worse(AdSuitability.LIMITED);
            }
        }
        return worst;
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
