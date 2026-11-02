package com.example.oops.service;

import com.example.oops.analyzer.AnalysisContext;
import com.example.oops.analyzer.ContentAnalyzer;
import com.example.oops.client.AnalysisServerClient;
import com.example.oops.client.OpenAiClient;
import com.example.oops.config.OopsProperties;
import com.example.oops.domain.*;
import com.example.oops.fusion.FindingFusionService;
import com.example.oops.repository.VideoRepository;
import com.example.oops.transcript.TranscriptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 분석 전체 흐름을 조립하는 곳.
 *
 *   STT → (선택한 전체 OCR) → 분석기 실행/필요 후보 장면 확인 → 리포트
 *
 * 진행 상태 갱신은 JobProgressService 를 통해 별도 트랜잭션으로 나간다.
 * 외부 계산에는 트랜잭션을 열지 않는다. 결과와 완료 상태는 ResultWriter가 함께 커밋한다.
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
    private final FindingFusionService fusionService;
    private final AnalysisServerClient analysisServerClient;
    private final ReportBuilder reportBuilder;
    private final JobProgressService progressService;
    private final VideoRepository videoRepository;
    private final OpenAiClient openAiClient;
    private final AnalysisResultWriter resultWriter;

    /** 분석기 하나의 실행 결과 */
    record AnalyzerRun(String key, List<RiskFinding> findings, AnalyzerStatus status, String message, long tookMs,
                       com.example.oops.analyzer.AnalyzerTrace trace) {
        AnalyzerRun(String key, List<RiskFinding> findings, AnalyzerStatus status, String message, long tookMs) {
            this(key, findings, status, message, tookMs, null);
        }
    }

    private volatile java.util.concurrent.ExecutorService analyzerPool;

    /** 분석기를 동시에 돌리는 스레드. 영상 동시 처리 수 × 분석기 수 정도면 충분하다. */
    private java.util.concurrent.ExecutorService analyzerPool() {
        var p = analyzerPool;
        if (p == null) {
            synchronized (this) {
                if (analyzerPool == null) {
                    var count = new java.util.concurrent.atomic.AtomicInteger();
                    analyzerPool = java.util.concurrent.Executors.newCachedThreadPool(r -> {
                        Thread t = new Thread(r, "analyzer-" + count.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    });
                }
                p = analyzerPool;
            }
        }
        return p;
    }

    @jakarta.annotation.PreDestroy
    void shutdownAnalyzerPool() {
        if (analyzerPool != null) analyzerPool.shutdownNow();
    }

    /**
     * 분석기 하나를 돌리고 수행 결과를 정리한다. 분석기 하나가 실패해도 다른 분석기 결과는 살린다.
     * AI 호출이 실패했으면 결과가 비어 있어도 SUCCESS 로 적지 않는다('봤는데 없음'과 '못 봄'은 다르다).
     */
    AnalyzerRun runAnalyzer(ContentAnalyzer analyzer, AnalysisContext context, OpenAiClient.CallScope scope) {
        long start = System.currentTimeMillis();
        try {
            List<RiskFinding> produced = analyzer.analyze(context);
            String notice = analyzer.consumeCoverageNotice(context).orElse(null);
            var trace = analyzer.consumeTrace(context).orElse(null);
            long took = System.currentTimeMillis() - start;
            log.info("[pipeline] 분석기별 결과 analyzer={} → {}건 ({}초)", analyzer.key(), produced.size(), took / 1000);
            if (scope.failures() > 0) {
                String why = scope.failureReason().orElse("AI 호출이 실패했습니다.");
                log.warn("[pipeline] {} AI 호출 {}건 실패 — {}", analyzer.key(), scope.failures(), why);
                AnalyzerStatus status = notice == null && produced.isEmpty() ? AnalyzerStatus.FAILED : AnalyzerStatus.PARTIAL;
                return new AnalyzerRun(analyzer.key(), produced, status, notice == null ? why : notice + " " + why, took, trace);
            }
            return new AnalyzerRun(analyzer.key(), produced, coverageStatus(AnalyzerStatus.SUCCESS, notice), notice, took, trace);
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            log.error("[pipeline] {} 실패, 건너뜁니다", analyzer.key(), e);
            return new AnalyzerRun(analyzer.key(), List.of(), AnalyzerStatus.FAILED, "분석 중 오류가 발생했습니다.",
                    System.currentTimeMillis() - start);
        } finally {
            analyzer.consumeCoverageNotice(context);
            analyzer.consumeTrace(context);
            analyzer.consumeContextLimitNotice(context);
        }
    }


    /** Synchronous worker body; AnalysisDispatchService owns bounded async admission. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void run(Long jobId) {
        Long videoId;
        try {
            videoId = progressService.begin(jobId);
            if (videoId == null) {
                log.info("[pipeline] 시작 전 취소되었거나 종료된 작업입니다. jobId={}", jobId);
                return;
            }
            log.info("[pipeline] 분석 시작 jobId={} videoId={}", jobId, videoId);
        } catch (Exception e) {
            log.error("[pipeline] 잡을 시작할 수 없습니다. jobId={}", jobId, e);
            return;
        }

        Video video;
        try {
            video = videoRepository.findById(videoId).orElse(null);
        } catch (Exception e) {
            log.error("[pipeline] 영상 입력 조회 실패 jobId={}", jobId, e);
            progressService.fail(jobId, "ANALYSIS_FAILED", "영상 입력을 조회하지 못했습니다.");
            return;
        }
        if (video == null) {
            progressService.fail(jobId, "VIDEO_NOT_FOUND", "영상을 찾을 수 없습니다.");
            return;
        }

        // 단계별 소요 시간을 재서 마지막에 한 줄로 보여준다.
        // 어디가 느린지 짐작하지 않고 숫자로 확인하기 위해서다.
        Map<String, Long> elapsed = new LinkedHashMap<>();
        long pipelineStart = System.currentTimeMillis();

        try {
            openAiClient.beginVideo(videoId);   // 토큰 사용량 누적 시작

            // 1. 음성 → 타임스탬프 대본
            checkStillActive(jobId);
            progressService.update(jobId, AnalysisStage.STT, 15);
            long mark = System.currentTimeMillis();
            Map<CoverageStep, AnalysisCoverage> coverage = new LinkedHashMap<>();
            List<TranscriptSegment> transcript;
            // 오래 걸리는 단계 동안 진행률을 조금씩 올린다(멈춘 것처럼 보이지 않게, WebSocket 이 조용해지지 않게).
            try (var ticker = new ProgressTicker(jobId, AnalysisStage.STT, 15, 40)) {
                transcript = transcriptService.extract(video);
            }
            checkStillActive(jobId);
            elapsed.put("STT", System.currentTimeMillis() - mark);

            // 수행 여부를 기록한다. 0건과 실패는 다르다.
            if (transcript.isEmpty()) {
                log.warn("[pipeline] 대본이 비었습니다. videoId={}", videoId);
                record(coverage, video, CoverageStep.STT, AnalyzerStatus.FAILED,
                        analysisServerClient.lastFailureDetail()
                                .orElse("음성을 글자로 옮기지 못했습니다."));
            } else {
                record(coverage, video, CoverageStep.STT, AnalyzerStatus.SUCCESS,
                        !video.hasStoredTranscript() ? null : "저장 전사를 재사용했습니다. 새 음성인식을 수행하지 않았습니다.");
            }

            // redesign-v3: 입력은 발언(전사)뿐이다. 화면 글자(OCR)·장면 분석·영상 유형 판별은 하지 않는다.
            List<ScreenText> screenTexts = List.of();
            ContentGenre genre = video.genreOrGeneral();

            // 발언을 못 뽑았으면 분석을 한 게 아니다. '확인할 지점 0곳'으로 끝내면 "검수했는데 문제없다"로 읽힌다.
            if (transcript.isEmpty()) {
                String detail = analysisServerClient.lastFailureDetail().orElse("음성을 글자로 옮기지 못해 발언을 분석하지 못했습니다.");
                log.error("[pipeline] videoId={} 분석 불가: {}", videoId, detail);
                progressService.fail(jobId, "ANALYSIS_FAILED", detail);
                return;
            }

            AnalysisContext context = new AnalysisContext(video, genre, transcript, screenTexts,
                    com.example.oops.analyzer.ReviewInput.from(transcript, screenTexts),
                    () -> progressService.isStopped(jobId));

            // 3. 분석기 실행 — 맥락 확인과 사실대조는 서로 독립이라 동시에 돈다.
            // 분석기마다 하위 호출 집계(child scope)를 써서 실패는 따로 판정하고 사용량은 영상 전체에 합친다.
            List<ContentAnalyzer> active = activeAnalyzers();
            List<RiskFinding> candidates = new ArrayList<>();
            List<AnalysisResultWriter.TraceEntry> traces = new ArrayList<>();
            String criteriaVersion = null;
            progressService.update(jobId, AnalysisStage.TEXT_RISK, 45,
                    active.stream().map(ContentAnalyzer::displayName).collect(java.util.stream.Collectors.joining(", ")) + " 중");
            var videoScope = openAiClient.currentScope();
            Map<String, OpenAiClient.CallScope> analyzerScopes = new LinkedHashMap<>();
            List<java.util.concurrent.CompletableFuture<AnalyzerRun>> runs = new ArrayList<>();
            for (ContentAnalyzer analyzer : active) {
                checkStillActive(jobId);
                if (!analyzer.supports(context)) {
                    String why = analyzer.unsupportedReason(context).orElse(openAiClient.isEnabled()
                            ? "이 영상에는 해당하지 않아 건너뛰었습니다."
                            : "AI 키가 없어 이 단계를 수행하지 못했습니다.");
                    log.info("[pipeline] {} 스킵 (조건 불충족)", analyzer.key());
                    record(coverage, video, CoverageStep.of(analyzer.key()), AnalyzerStatus.SKIPPED, why);
                    continue;
                }
                var scope = videoScope.child(analyzer.key());
                analyzerScopes.put(analyzer.key(), scope);
                runs.add(java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> openAiClient.withScope(scope, () -> runAnalyzer(analyzer, context, scope)), analyzerPool()));
            }
            var analyzerTicker = new ProgressTicker(jobId, AnalysisStage.TEXT_RISK, 45, 82);
            try {
            for (var future : runs) {
                AnalyzerRun run;
                try {
                    run = future.join();
                } catch (java.util.concurrent.CompletionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof java.util.concurrent.CancellationException || progressService.isStopped(jobId)) {
                        throw new AnalysisStoppedException();
                    }
                    throw e;
                }
                checkStillActive(jobId);
                elapsed.put(run.key(), run.tookMs());
                candidates.addAll(run.findings());
                if (run.trace() != null) {
                    traces.add(new AnalysisResultWriter.TraceEntry(run.key(), run.trace().json()));
                    if (criteriaVersion == null) criteriaVersion = run.trace().criteriaVersion();
                }
                record(coverage, video, CoverageStep.of(run.key()), run.status(), run.message());
            }

            } finally {
                analyzerTicker.close();
            }

            // 켜지지 않은 단계도 보고 대상이다. 아무 말이 없으면 돌았다고 오해한다.
            for (CoverageStep step : CoverageStep.values()) {
                coverage.putIfAbsent(step, AnalysisCoverage.of(
                        video, step, AnalyzerStatus.NOT_ENABLED, "현재 사용하지 않는 기능입니다."));
            }

            // 4. 다중 후보 병합 + 우선순위
            checkStillActive(jobId);
            progressService.update(jobId, AnalysisStage.MULTIMODAL, 85, "논란 후보 정리 중");
            List<RiskFinding> findings = fusionService.fuse(candidates);

            // 명세 §9 — 모든 구간이 영상 길이 안에 있어야 한다.
            // OCR 은 프레임 간격만큼 endMs 를 잡아서 마지막 자막이 영상 밖으로 나간다.
            Long durationMs = video.durationMs();
            if (durationMs != null) {
                findings.forEach(f -> f.clampTo(durationMs));
            }

            // 5. 리포트 집계
            progressService.update(jobId, AnalysisStage.FINALIZING, 92);
            checkStillActive(jobId);
            int riskScore = reportBuilder.calculateRiskScore(findings);
            String summary = reportBuilder.buildSummary(findings);

            if (!resultWriter.save(jobId, new AnalysisResultWriter.Result(video, genre, transcript, screenTexts,
                    coverage.values(), findings, riskScore, summary, openAiClient.videoUsage().model(),
                    criteriaVersion, video.hasStoredTranscript(), traces))) {
                throw new AnalysisStoppedException();
            }

            long total = System.currentTimeMillis() - pipelineStart;
            log.info("[pipeline] 완료 jobId={} videoId={} score={} events={} 총 {}초",
                    jobId, videoId, riskScore, findings.size(), total / 1000);
            log.info("[pipeline] 소요 내역 jobId={} — {}", jobId, formatElapsed(elapsed, total));
            logCost(videoId, video.getDurationSec(), video.hasStoredTranscript(), videoScope, analyzerScopes, active);

        } catch (AnalysisStoppedException e) {
            log.info("[pipeline] 종료되었거나 대체된 작업 결과를 저장하지 않습니다. jobId={}", jobId);
        } catch (java.util.concurrent.CancellationException e) {
            log.info("[pipeline] 외부 분석 호출 전에 취소된 작업을 저장하지 않습니다. jobId={}", jobId);
        } catch (Exception e) {
            log.error("[pipeline] 실패 jobId={}", jobId, e);
            progressService.fail(jobId, "ANALYSIS_FAILED", "분석 처리 또는 결과 저장에 실패했습니다.");
        } finally {
            openAiClient.endVideo();
        }
    }

    private void checkStillActive(Long jobId) {
        if (progressService.isStopped(jobId)) {
            throw new AnalysisStoppedException();
        }
    }

    /**
     * 오래 걸리는 단계가 도는 동안 진행률을 조금씩 올려 준다.
     *
     * 화면 글자 인식은 한 번 부르면 몇 분씩 돌아온다.
     * 그동안 아무 메시지도 안 나가면 두 가지 문제가 생긴다.
     *
     *   1. 화면이 멈춘 것처럼 보여 사용자가 취소를 누른다
     *   2. WebSocket 이 조용해져서 앞단 프록시가 연결을 끊는다
     *
     * 2번은 하트비트로도 막지만, 진행률이 멈춰 있는 건 그것과 별개다.
     *
     * 실제 진척도를 알 수는 없다. 파이썬이 한 번에 다 하고 돌려주기 때문이다.
     * 그래서 **끝 지점에 다가가되 닿지는 않게** 올린다.
     * 남은 거리의 일부만 줄이는 방식이라 100%가 되어 멈춰 있는 일은 없다.
     * 진짜 완료는 다음 단계가 찍는다.
     */
    private final class ProgressTicker implements AutoCloseable {

        private static final long INTERVAL_MS = 5_000;

        private final Thread thread;
        private volatile boolean running = true;

        ProgressTicker(Long jobId, AnalysisStage stage, int from, int to) {
            this.thread = new Thread(() -> {
                double current = from;
                while (running) {
                    try {
                        Thread.sleep(INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!running) return;

                    // 남은 거리의 12% 씩 좁힌다. 끝에 가까울수록 느려진다.
                    current += (to - current) * 0.12;
                    try {
                        progressService.update(jobId, stage, (int) current);
                    } catch (Exception e) {
                        // 진행률 갱신 실패로 분석을 멈출 이유는 없다
                        log.debug("[pipeline] 진행률 갱신 실패: {}", e.getMessage());
                    }
                }
            }, "progress-ticker");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        @Override
        public void close() {
            running = false;
            thread.interrupt();
        }
    }

    static AnalyzerStatus coverageStatus(AnalyzerStatus status, String notice) {
        return status == AnalyzerStatus.SUCCESS && notice != null && !notice.isBlank()
                ? AnalyzerStatus.PARTIAL : status;
    }

    private static final class AnalysisStoppedException extends RuntimeException {}

    /** 어디에 시간을 썼는지 비중과 함께 한 줄로 정리한다. */
    private String formatElapsed(Map<String, Long> elapsed, long total) {
        if (total <= 0) return "측정 없음";
        return elapsed.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> "%s %d초(%d%%)".formatted(
                        e.getKey(), e.getValue() / 1000, e.getValue() * 100 / total))
                .collect(java.util.stream.Collectors.joining(", "));
    }

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
    /**
     * 영상 하나에 든 API 비용을 업체·분석기별로 남긴다.
     *
     *   [cost] videoId=470 ── API 비용 (추정) ──
     *   [cost]   OpenAI · 맥락 기준 검토(context-review)  호출 24회 · 입력 151,200(캐시 120,000) · 출력 6,100   $0.0151
     *   [cost]   OpenAI · 사실대조(fact-check)            호출 33회 · ...                                       $0.0040
     *   [cost]   Groq · 음성인식                           13.2분                                               $0.0244
     *   [cost]   Perplexity · 검색                         17회 × $0.0050                                      $0.0850
     *   [cost]   Serper · 검색                              6회 × $0.0010                                      $0.0060
     *   [cost]   합계 $0.1345 (약 188원) · 영상 1분당 약 14원
     *
     * OpenAI 는 응답의 실제 토큰 수 × 단가, Groq 은 영상 길이 × 분당 단가, 검색은 요청 수 × 건당 단가(설정값)다.
     * 단가는 application.yml 의 oops.openai.pricing, oops.search.*.cost-per-* 로 바꾼다.
     */
    private void logCost(Long videoId, Integer durationSec, boolean transcriptReused, OpenAiClient.CallScope videoScope,
                         Map<String, OpenAiClient.CallScope> analyzerScopes, List<ContentAnalyzer> analyzers) {
        OpenAiClient.TokenUsage total = openAiClient.usageOf(videoScope);
        var pricing = total.pricing();
        double sttUsd = sttEstimateUsd(durationSec, pricing.sttUsdPerMinute(), transcriptReused);
        double sum = sttUsd;

        log.info("[cost] videoId={} ── API 비용 (추정) ──", videoId);
        for (var e : analyzerScopes.entrySet()) {
            var u = openAiClient.usageOf(e.getValue());
            String name = analyzers.stream().filter(a -> a.key().equals(e.getKey())).map(ContentAnalyzer::displayName)
                    .findFirst().orElse(e.getKey());
            sum += u.costUsd();
            log.info("[cost]   OpenAI · {}({})  호출 {}회 · 입력 {}(캐시 {}) · 출력 {}   ${}", name, e.getKey(), u.calls(),
                    "%,d".formatted(u.promptTokens()), "%,d".formatted(u.cachedTokens()),
                    "%,d".formatted(u.completionTokens()), "%.4f".formatted(u.costUsd()));
        }
        if (transcriptReused) {
            log.info("[cost]   Groq · 음성인식  저장 전사 재사용 — 호출 없음   $0.0000");
        } else {
            log.info("[cost]   Groq · 음성인식  {}분 × ${}/분   ${}", durationSec == null ? "?" : "%.1f".formatted(durationSec / 60.0),
                    "%.5f".formatted(pricing.sttUsdPerMinute()), "%.4f".formatted(sttUsd));
        }
        for (var e : videoScope.external().entrySet()) {
            int n = (int) e.getValue()[0];
            double usd = e.getValue()[1];
            sum += usd;
            String label = switch (e.getKey()) {
                case "perplexity" -> "Perplexity · 검색";
                case "serper" -> "Serper · 검색";
                default -> e.getKey();
            };
            log.info("[cost]   {}  {}회 × ${}   ${}", label, n, "%.4f".formatted(n == 0 ? 0 : usd / n), "%.4f".formatted(usd));
        }
        long krw = Math.round(sum * pricing.krwRate());
        String perMinute = durationSec != null && durationSec > 0
                ? " · 영상 1분당 약 " + Math.round(krw / (durationSec / 60.0)) + "원 (" + "%.1f".formatted(durationSec / 60.0) + "분)" : "";
        log.info("[cost]   합계 ${} (약 {}원){}", "%.4f".formatted(sum), krw, perMinute);
    }

    static double sttEstimateUsd(Integer durationSec,double perMinute,boolean transcriptReused) {
        return transcriptReused || durationSec==null || durationSec<=0 ? 0 : durationSec/60.0*perMinute;
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
        String combined = AnalysisCoverage.combineMessages(existing.getMessage(), message);
        AnalysisCoverage updated = AnalysisCoverage.of(video, step, merged, combined);
        if (Boolean.TRUE.equals(existing.getContextLimited())) updated.markContextLimited(null);
        coverage.put(step, updated);
    }

    /** application.yml 에 켜둔 분석기만, 설정 순서대로 실행한다. */
    private List<ContentAnalyzer> activeAnalyzers() {
        List<String> enabled = properties.analysis().enabledAnalyzers();
        return enabled.stream()
                .flatMap(key -> analyzers.stream().filter(a -> a.key().equals(key)))
                .toList();
    }
}
