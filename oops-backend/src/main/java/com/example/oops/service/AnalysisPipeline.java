package com.example.oops.service;

import com.example.oops.analyzer.AnalysisContext;
import com.example.oops.analyzer.ContentAnalyzer;
import com.example.oops.config.AsyncConfig;
import com.example.oops.config.OopsProperties;
import com.example.oops.domain.*;
import com.example.oops.fusion.FindingFusionService;
import com.example.oops.genre.GenreDetector;
import com.example.oops.repository.AnalysisReportRepository;
import com.example.oops.repository.RiskFindingRepository;
import com.example.oops.repository.VideoRepository;
import com.example.oops.screentext.ScreenTextService;
import com.example.oops.transcript.TranscriptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 분석 전체 흐름을 조립하는 곳.
 *
 *   STT → OCR → 분석기 실행 → 후보 병합/우선순위 → 리포트 집계
 *
 * 진행 상태 갱신은 JobProgressService 를 통해 별도 트랜잭션으로 나간다.
 * 이 메서드의 트랜잭션은 분석이 끝날 때까지 커밋되지 않으므로,
 * 여기서 직접 job 을 건드리면 프론트가 진행률을 실시간으로 볼 수 없다.
 *
 * 분석기가 늘어나도 이 클래스는 그대로다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisPipeline {

    private final List<ContentAnalyzer> analyzers;
    private final OopsProperties properties;
    private final TranscriptService transcriptService;
    private final ScreenTextService screenTextService;
    private final FindingFusionService fusionService;
    private final GenreDetector genreDetector;
    private final ReportBuilder reportBuilder;
    private final JobProgressService progressService;
    private final VideoRepository videoRepository;
    private final RiskFindingRepository findingRepository;
    private final AnalysisReportRepository reportRepository;

    @Async(AsyncConfig.ANALYSIS_EXECUTOR)
    @Transactional
    public void runAsync(Long jobId) {
        Long videoId;
        try {
            videoId = progressService.begin(jobId);
        } catch (Exception e) {
            log.error("[pipeline] 잡을 시작할 수 없습니다. jobId={}", jobId, e);
            return;
        }

        Video video = videoRepository.findById(videoId).orElse(null);
        if (video == null) {
            progressService.fail(jobId, "VIDEO_NOT_FOUND", "영상을 찾을 수 없습니다.");
            return;
        }

        try {
            video.updateStatus(AnalysisStatus.PROCESSING);

            // 1. 음성 → 타임스탬프 대본
            progressService.update(jobId, AnalysisStage.STT, 15);
            List<TranscriptSegment> transcript = transcriptService.extractAndSave(video);
            if (transcript.isEmpty()) {
                log.warn("[pipeline] 대본이 비었습니다. videoId={}", videoId);
            }

            // 2. 화면 → OCR 자막 (OCR 이 없으면 빈 리스트로 진행)
            progressService.update(jobId, AnalysisStage.OCR, 35);
            List<ScreenText> screenTexts = screenTextService.extractAndSave(video);

            // 영상 유형을 정한다. 업로드할 때 지정했으면 그대로 쓰고, 없으면 대본을 보고 판별한다.
            // 유형에 따라 실행되는 분석기가 달라지므로 분석기를 돌리기 전에 정해야 한다.
            ContentGenre genre = video.getGenre();
            if (genre == null) {
                progressService.update(jobId, AnalysisStage.TEXT_RISK, 42, "영상 유형 판별 중");
                genre = genreDetector.detect(transcript, screenTexts);
                video.assignGenre(genre);
            }
            log.info("[pipeline] videoId={} 유형={}", videoId, genre);

            AnalysisContext context = new AnalysisContext(video, genre, transcript, screenTexts);

            // 3. 분석기 실행 → 논란 후보 수집
            findingRepository.deleteByVideoId(videoId);
            List<ContentAnalyzer> active = activeAnalyzers();
            List<RiskFinding> candidates = new ArrayList<>();

            int index = 0;
            for (ContentAnalyzer analyzer : active) {
                index++;
                progressService.update(jobId, stageOf(analyzer),
                        45 + (35 * index / Math.max(1, active.size())),
                        analyzer.displayName() + " 중");

                if (!analyzer.supports(context)) {
                    log.info("[pipeline] {} 스킵 (조건 불충족)", analyzer.key());
                    continue;
                }
                try {
                    candidates.addAll(analyzer.analyze(context));
                } catch (Exception e) {
                    // 분석기 하나가 죽어도 나머지 결과는 살린다
                    log.error("[pipeline] {} 실패, 건너뜁니다", analyzer.key(), e);
                }
            }

            // 4. 다중 후보 병합 + 우선순위
            progressService.update(jobId, AnalysisStage.MULTIMODAL, 85, "논란 후보 정리 중");
            List<RiskFinding> findings = fusionService.fuse(candidates);
            findingRepository.saveAll(findings);

            // 5. 리포트 집계
            progressService.update(jobId, AnalysisStage.FINALIZING, 92);
            int riskScore = reportBuilder.calculateRiskScore(findings);
            String summary = reportBuilder.buildSummary(findings);

            reportRepository.findByVideoId(videoId)
                    .ifPresentOrElse(
                            r -> r.update(riskScore, findings.size(), summary),
                            () -> reportRepository.save(
                                    new AnalysisReport(video, riskScore, findings.size(), summary))
                    );

            video.updateStatus(AnalysisStatus.COMPLETED);
            progressService.complete(jobId);
            log.info("[pipeline] 완료 videoId={} score={} events={}",
                    videoId, riskScore, findings.size());

        } catch (Exception e) {
            log.error("[pipeline] 실패 jobId={}", jobId, e);
            video.updateStatus(AnalysisStatus.FAILED);
            progressService.fail(jobId, "ANALYSIS_FAILED", e.getMessage());
        }
    }

    /** 분석기 종류에 맞는 진행 단계를 고른다. 프론트가 단계 라벨을 그리는 데 쓴다. */
    private AnalysisStage stageOf(ContentAnalyzer analyzer) {
        return switch (analyzer.key()) {
            case "screen-text", "screen-text-risk" -> AnalysisStage.OCR;
            case "caption-mismatch", "timeliness" -> AnalysisStage.MULTIMODAL;
            case "fact-check" -> AnalysisStage.TEXT_RISK;
            case "pose" -> AnalysisStage.SCENE_DETECTION;
            default -> AnalysisStage.TEXT_RISK;
        };
    }

    /** application.yml 에 켜둔 분석기만, 설정 순서대로 실행한다. */
    private List<ContentAnalyzer> activeAnalyzers() {
        List<String> enabled = properties.analysis().enabledAnalyzers();
        return enabled.stream()
                .flatMap(key -> analyzers.stream().filter(a -> a.key().equals(key)))
                .toList();
    }
}
