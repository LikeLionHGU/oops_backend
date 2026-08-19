package com.example.oops.service;

import com.example.oops.analyzer.AnalysisContext;
import com.example.oops.analyzer.ContentAnalyzer;
import com.example.oops.client.AnalysisServerClient;
import com.example.oops.client.OpenAiClient;
import com.example.oops.config.AsyncConfig;
import com.example.oops.config.OopsProperties;
import com.example.oops.domain.*;
import com.example.oops.fusion.FindingFusionService;
import com.example.oops.genre.GenreDetector;
import com.example.oops.repository.AnalysisCoverageRepository;
import com.example.oops.repository.AnalysisReportRepository;
import com.example.oops.repository.ReviewActionRepository;
import com.example.oops.repository.ReviewReferenceRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final AnalysisServerClient analysisServerClient;
    private final ReportBuilder reportBuilder;
    private final JobProgressService progressService;
    private final VideoRepository videoRepository;
    private final RiskFindingRepository findingRepository;
    private final AnalysisCoverageRepository coverageRepository;
    private final ReviewActionRepository actionRepository;
    private final OpenAiClient openAiClient;
    private final ReviewReferenceRepository referenceRepository;
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

        // 단계별 소요 시간을 재서 마지막에 한 줄로 보여준다.
        // 어디가 느린지 짐작하지 않고 숫자로 확인하기 위해서다.
        Map<String, Long> elapsed = new LinkedHashMap<>();
        long pipelineStart = System.currentTimeMillis();

        try {
            video.updateStatus(AnalysisStatus.PROCESSING);
            openAiClient.beginVideo(videoId);   // 토큰 사용량 누적 시작

            // 1. 음성 → 타임스탬프 대본
            progressService.update(jobId, AnalysisStage.STT, 15);
            long mark = System.currentTimeMillis();
            List<TranscriptSegment> transcript = transcriptService.extractAndSave(video);
            elapsed.put("STT", System.currentTimeMillis() - mark);

            // 수행 여부를 기록한다. 0건과 실패는 다르다.
            Map<CoverageStep, AnalysisCoverage> coverage = new LinkedHashMap<>();
            if (transcript.isEmpty()) {
                log.warn("[pipeline] 대본이 비었습니다. videoId={}", videoId);
                record(coverage, video, CoverageStep.STT, AnalyzerStatus.FAILED,
                        analysisServerClient.lastFailureDetail()
                                .orElse("음성을 글자로 옮기지 못했습니다."));
            } else {
                record(coverage, video, CoverageStep.STT, AnalyzerStatus.SUCCESS, null);
            }

            // 2. 화면 → OCR 자막 (OCR 이 없으면 빈 리스트로 진행)
            progressService.update(jobId, AnalysisStage.OCR, 35);
            mark = System.currentTimeMillis();
            List<ScreenText> screenTexts = screenTextService.extractAndSave(video);
            elapsed.put("OCR", System.currentTimeMillis() - mark);

            // 글자가 없는 영상도 있으므로 0건이 곧 실패는 아니다.
            // 분석 서버가 사유를 남겼을 때만 실패로 본다.
            if (screenTexts.isEmpty() && analysisServerClient.lastFailureDetail().isPresent()) {
                record(coverage, video, CoverageStep.OCR, AnalyzerStatus.FAILED,
                        analysisServerClient.lastFailureDetail().orElse(null));
            } else if (screenTexts.isEmpty()) {
                // 돌긴 돌았는데 한 글자도 못 찾았다.
                // 자막 없는 영상이면 정상이지만, 자막이 있는데 못 읽은 것일 수도 있다.
                // 그 둘을 우리가 구분할 방법이 없으므로 사용자에게 그대로 알린다.
                // 그냥 '완료' 로만 두면 "화면은 확인했다" 로 읽힌다.
                record(coverage, video, CoverageStep.OCR, AnalyzerStatus.SUCCESS,
                        "화면에서 글자를 찾지 못했습니다. 영상에 자막이 있다면 인식에 실패한 것일 수 있습니다.");
            } else {
                record(coverage, video, CoverageStep.OCR, AnalyzerStatus.SUCCESS, null);
            }
            record(coverage, video, CoverageStep.VISUAL, AnalyzerStatus.NOT_ENABLED,
                    "화면 자료 확인은 아직 제공하지 않습니다.");

            // 영상 유형을 정한다. 업로드할 때 지정했으면 그대로 쓰고, 없으면 대본을 보고 판별한다.
            // 유형에 따라 실행되는 분석기가 달라지므로 분석기를 돌리기 전에 정해야 한다.
            ContentGenre genre = video.getGenre();
            if (genre == null) {
                progressService.update(jobId, AnalysisStage.TEXT_RISK, 42, "영상 유형 판별 중");
                mark = System.currentTimeMillis();
                openAiClient.beginAnalyzer("genre");
                genre = genreDetector.detect(transcript, screenTexts);
                video.assignGenre(genre);
                elapsed.put("유형판별", System.currentTimeMillis() - mark);
            }
            log.info("[pipeline] videoId={} 유형={}", videoId, genre);

            // 발언도 자막도 못 뽑았으면 분석을 한 게 아니다.
            //
            // 여기서 그냥 진행하면 모든 분석기가 스킵되고
            // '완료 · 확인할 지점 0곳' 으로 끝난다.
            // 사용자에게는 "검수했는데 문제없다" 로 읽히지만 실제로는
            // 아무것도 보지 못한 것이다. 이건 거짓말이다.
            if (transcript.isEmpty() && screenTexts.isEmpty()) {
                String detail = analysisServerClient.lastFailureDetail()
                        .orElse("영상에서 음성과 화면 글자를 모두 읽지 못했습니다.");
                log.error("[pipeline] videoId={} 분석 불가: {}", videoId, detail);

                video.updateStatus(AnalysisStatus.FAILED);
                progressService.fail(jobId, "ANALYSIS_FAILED", detail);
                return;
            }

            AnalysisContext context = new AnalysisContext(video, genre, transcript, screenTexts);

            // 3. 분석기 실행 → 논란 후보 수집
            // 참고 자료가 risk_finding 을 참조하므로 먼저 지운다
            // 검수 액션도 지운다. 후보 id 가 새로 발급되므로 옛 액션은 엉뚱한 곳을 가리킨다.
            actionRepository.deleteByVideoId(videoId);
            referenceRepository.deleteByVideoId(videoId);
            findingRepository.deleteByVideoId(videoId);
            List<ContentAnalyzer> active = activeAnalyzers();
            List<RiskFinding> candidates = new ArrayList<>();

            int index = 0;
            for (ContentAnalyzer analyzer : active) {
                index++;
                progressService.update(jobId, stageOf(analyzer),
                        45 + (35 * index / Math.max(1, active.size())),
                        analyzer.displayName() + " 중");

                CoverageStep step = CoverageStep.of(analyzer.key());

                if (!analyzer.supports(context)) {
                    String why = openAiClient.isEnabled()
                            ? "이 영상에는 해당하지 않아 건너뛰었습니다."
                            : "AI 키가 없어 이 단계를 수행하지 못했습니다.";
                    log.info("[pipeline] {} 스킵 (조건 불충족)", analyzer.key());
                    record(coverage, video, step, AnalyzerStatus.SKIPPED, why);
                    continue;
                }
                try {
                    long analyzerStart = System.currentTimeMillis();
                    openAiClient.beginAnalyzer(analyzer.key());

                    List<RiskFinding> produced = analyzer.analyze(context);

                    long took = System.currentTimeMillis() - analyzerStart;
                    elapsed.put(analyzer.key(), took);
                    candidates.addAll(produced);
                    log.info("[pipeline] 분석기별 결과 {} → {}건 ({}초)",
                            analyzer.key(), produced.size(), took / 1000);

                    // 예외 없이 끝나도 AI 호출이 전부 실패했을 수 있다.
                    // 요청 한도에 걸리면 조용히 빈손으로 돌아오는데,
                    // 그걸 성공으로 적으면 사용자는 '봤는데 없다' 로 읽는다.
                    if (openAiClient.failureCount() > 0) {
                        String why = openAiClient.failureReason()
                                .orElse("AI 호출이 실패했습니다.");
                        log.warn("[pipeline] {} AI 호출 {}건 실패 — {}",
                                analyzer.key(), openAiClient.failureCount(), why);
                        record(coverage, video, step, AnalyzerStatus.FAILED, why);
                    } else {
                        record(coverage, video, step, AnalyzerStatus.SUCCESS, null);
                    }
                } catch (Exception e) {
                    // 분석기 하나가 죽어도 나머지 결과는 살린다
                    log.error("[pipeline] {} 실패, 건너뜁니다", analyzer.key(), e);
                    record(coverage, video, step, AnalyzerStatus.FAILED,
                            "분석 중 오류가 발생했습니다.");
                }
            }

            // 켜지지 않은 단계도 보고 대상이다. 아무 말이 없으면 돌았다고 오해한다.
            for (CoverageStep step : CoverageStep.values()) {
                coverage.putIfAbsent(step, AnalysisCoverage.of(
                        video, step, AnalyzerStatus.NOT_ENABLED, "현재 사용하지 않는 기능입니다."));
            }
            coverageRepository.deleteByVideoId(videoId);
            coverageRepository.saveAll(coverage.values());

            // 4. 다중 후보 병합 + 우선순위
            progressService.update(jobId, AnalysisStage.MULTIMODAL, 85, "논란 후보 정리 중");
            List<RiskFinding> findings = fusionService.fuse(candidates);

            // 명세 §9 — 모든 구간이 영상 길이 안에 있어야 한다.
            // OCR 은 프레임 간격만큼 endMs 를 잡아서 마지막 자막이 영상 밖으로 나간다.
            Long durationMs = video.durationMs();
            if (durationMs != null) {
                findings.forEach(f -> f.clampTo(durationMs));
            }
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

            long total = System.currentTimeMillis() - pipelineStart;
            log.info("[pipeline] 완료 videoId={} score={} events={} 총 {}초",
                    videoId, riskScore, findings.size(), total / 1000);
            log.info("[pipeline] 소요 내역 — {}", formatElapsed(elapsed, total));
            logCost(videoId, video.getDurationSec());

        } catch (Exception e) {
            log.error("[pipeline] 실패 jobId={}", jobId, e);
            video.updateStatus(AnalysisStatus.FAILED);
            progressService.fail(jobId, "ANALYSIS_FAILED", e.getMessage());
        }
    }

    /** 어디에 시간을 썼는지 비중과 함께 한 줄로 정리한다. */
    private String formatElapsed(Map<String, Long> elapsed, long total) {
        if (total <= 0) return "측정 없음";
        return elapsed.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> "%s %d초(%d%%)".formatted(
                        e.getKey(), e.getValue() / 1000, e.getValue() * 100 / total))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /** 분석기 종류에 맞는 진행 단계를 고른다. 프론트가 단계 라벨을 그리는 데 쓴다. */
    private AnalysisStage stageOf(ContentAnalyzer analyzer) {
        return switch (analyzer.key()) {
            case "screen-text", "screen-text-review" -> AnalysisStage.OCR;
            case "caption-mismatch", "context-check" -> AnalysisStage.MULTIMODAL;
            case "entity-check", "monetization" -> AnalysisStage.TEXT_RISK;
            case "pose" -> AnalysisStage.SCENE_DETECTION;
            default -> AnalysisStage.TEXT_RISK;
        };
    }

    /** application.yml 에 켜둔 분석기만, 설정 순서대로 실행한다. */
    /**
     * 이 영상에 실제로 얼마가 나갔는지 한 줄로 정리한다.
     *
     * 호출 하나하나는 [openai-usage] 로 이미 찍히지만, 그걸 손으로 더하고 있을 수는 없다.
     * 영상 길이별 원가를 재려면 결국 이 한 줄이 필요하다.
     *
     * 음성 인식을 같이 세는 이유는 그쪽이 대부분을 차지하기 때문이다.
     * 긴 영상에서는 LLM 비용보다 음성 인식이 훨씬 크다.
     * LLM 만 보여주면 "생각보다 싸네" 라고 잘못 판단하게 된다.
     */
    private void logCost(Long videoId, Integer durationSec) {
        OpenAiClient.TokenUsage usage = openAiClient.videoUsage();

        double sttUsd = 0;
        if (durationSec != null && durationSec > 0) {
            sttUsd = durationSec / 60.0 * usage.pricing().sttUsdPerMinute();
        }
        double totalUsd = usage.costUsd() + sttUsd;
        double krw = totalUsd * usage.pricing().krwRate();

        // 한도 사용량은 비용보다 먼저 남긴다.
        // 호출이 전부 실패했을 때가 가장 알고 싶은 순간인데,
        // 아래 조기 반환 뒤에 두면 바로 그때 안 찍힌다.
        logRequestBudget(videoId, durationSec, usage);

        if (usage.isEmpty() && sttUsd == 0) {
            return;
        }

        log.info("[openai-cost] videoId={} 호출 {}회 · 입력 {}토큰(캐시 {}) · 출력 {}토큰",
                videoId, usage.calls(), usage.promptTokens(),
                usage.cachedTokens(), usage.completionTokens());

        log.info("[openai-cost] videoId={} 분석 ${} + 음성인식 ${} = ${} (약 {}원)",
                videoId,
                "%.5f".formatted(usage.costUsd()),
                "%.5f".formatted(sttUsd),
                "%.5f".formatted(totalUsd),
                Math.round(krw));

        if (durationSec != null && durationSec > 0) {
            log.info("[openai-cost] videoId={} 1분당 약 {}원 (영상 {}분)",
                    videoId,
                    Math.round(krw / (durationSec / 60.0)),
                    "%.1f".formatted(durationSec / 60.0));
        }
    }

    /**
     * 이 영상이 요청 한도를 얼마나 먹었는지, 60분이면 얼마일지 남긴다.
     *
     * 비용과 한도는 다른 이야기다.
     * 비용은 응답을 받은 호출에만 붙지만, **요청 한도는 거절당한 요청도 깎는다.**
     * 그래서 성공 호출 수만 보면 "1회 했는데 왜 한도에 걸리지" 가 된다.
     *
     * 60분 환산을 같이 찍는 이유는, 짧은 영상으로 시험한 뒤
     * 실제 대상(20~60분)에서 한도에 걸릴지 미리 알기 위해서다.
     * 요청 수는 대본 길이에 거의 비례하므로 이 환산이 꽤 잘 맞는다.
     */
    private void logRequestBudget(Long videoId, Integer durationSec,
                                  OpenAiClient.TokenUsage usage) {
        if (usage.requests() == 0) {
            return;
        }

        log.info("[openai-quota] videoId={} 요청 {}건 (성공 {} · 한도거절 {})",
                videoId, usage.requests(), usage.calls(), usage.rateLimited());

        Map<String, Long> byAnalyzer = openAiClient.requestsByAnalyzer();
        if (!byAnalyzer.isEmpty()) {
            log.info("[openai-quota] videoId={} 분석기별 — {}", videoId,
                    byAnalyzer.entrySet().stream()
                            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                            .map(e -> "%s %d건".formatted(e.getKey(), e.getValue()))
                            .collect(java.util.stream.Collectors.joining(", ")));
        }

        if (durationSec == null || durationSec <= 0) {
            return;
        }
        double minutes = durationSec / 60.0;

        // **총합을 그냥 60배 하면 안 된다.**
        //
        // 분석기 대부분은 상한이 걸려 있어서 영상이 길어져도 호출이 안 는다.
        //   entity-check 7회 / context-check 9회 / context-lexicon 2회 가 끝이다.
        // 늘어나는 건 대본을 창 단위로 훑는 분석기뿐이다.
        //
        // 짧은 영상일수록 고정 호출의 비중이 커서, 그냥 곱하면
        // 실제보다 몇 배 크게 나온다. 1분짜리 13건을 60배 하면 780건인데
        // 실제 60분 영상은 50~60건 수준이다.
        java.util.Set<String> scaling = analyzers.stream()
                .filter(ContentAnalyzer::scalesWithLength)
                .map(ContentAnalyzer::key)
                .collect(java.util.stream.Collectors.toSet());

        long fixed = 0;
        long scaled = 0;
        for (Map.Entry<String, Long> e : byAnalyzer.entrySet()) {
            if (scaling.contains(e.getKey())) {
                scaled += Math.round(e.getValue() / minutes * 60);
            } else {
                fixed += e.getValue();   // 길어져도 그대로다
            }
        }
        long projected = fixed + scaled;

        log.info("[openai-quota] videoId={} 60분 환산 약 {}건 (고정 {} + 길이비례 {}) · 토큰 약 {}",
                videoId, projected, fixed, scaled,
                Math.round((usage.promptTokens() + usage.completionTokens()) / minutes * 60));

        log.info("[openai-quota] videoId={} 하루 한도가 200건이면 60분짜리 약 {}편",
                videoId, Math.max(1, 200 / Math.max(1, projected)));
    }

    /**
     * 단계별 수행 결과를 모은다.
     *
     * 한 단계를 여러 분석기가 나눠 맡으므로 나쁜 쪽을 남긴다.
     * subtitle 은 성공했는데 speech-review 가 요청 한도로 죽었다면,
     * 사용자에게는 "발언 검토를 못 했다" 고 알려야 한다.
     */
    private void record(Map<CoverageStep, AnalysisCoverage> coverage, Video video,
                        CoverageStep step, AnalyzerStatus status, String message) {
        if (step == null) {
            return;   // 보고 대상이 아닌 분석기 (caption-mismatch 등)
        }
        AnalysisCoverage existing = coverage.get(step);
        if (existing == null) {
            coverage.put(step, AnalysisCoverage.of(video, step, status, message));
            return;
        }
        AnalyzerStatus merged = status.worseOf(existing.getStatus());
        if (merged != existing.getStatus()) {
            coverage.put(step, AnalysisCoverage.of(video, step, merged, message));
        }
    }

    private List<ContentAnalyzer> activeAnalyzers() {
        List<String> enabled = properties.analysis().enabledAnalyzers();
        return enabled.stream()
                .flatMap(key -> analyzers.stream().filter(a -> a.key().equals(key)))
                .toList();
    }
}
