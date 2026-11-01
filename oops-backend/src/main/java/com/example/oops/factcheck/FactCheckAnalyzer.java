package com.example.oops.factcheck;

import com.example.oops.analyzer.AnalysisContext;
import com.example.oops.analyzer.ContentAnalyzer;
import com.example.oops.client.OpenAiClient;
import com.example.oops.context.ContextProperties;
import com.example.oops.context.ContextReviewEngine;
import com.example.oops.context.LineContextBuilder.ContextLine;
import com.example.oops.domain.EvidenceSource;
import com.example.oops.domain.FindingSupport;
import com.example.oops.domain.ReviewReference;
import com.example.oops.domain.RiskCategory;
import com.example.oops.domain.RiskFinding;
import com.example.oops.domain.TimelineEventType;
import com.example.oops.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 사실대조 분석기. (redesign-v3, key: fact-check)
 *
 * 맥락 확인(context-review)과 독립으로, 같은 전사에서 따로 돈다. 맥락 후보 유무를 실행 조건으로 쓰지 않는다.
 * 반박(REFUTED)된 주장만 카드로 내고, 실제로 판단에 쓴 자료만 출처로 붙인다.
 * 검색어·자료·판정은 {storage}/fact-traces/{videoId}/{epochMs}.json 에 남긴다(텍스트).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FactCheckAnalyzer implements ContentAnalyzer {

    public static final String KEY = "fact-check";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OpenAiClient openAiClient;
    private final PerplexitySearch perplexity;
    private final SerperSearch serper;
    private final SourceClassifier classifier;
    private final FactCheckProperties properties;
    private final ContextProperties contextProperties;
    private final StorageService storage;

    private final ThreadLocal<String> notice = new ThreadLocal<>();
    private volatile ExecutorService pool;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String displayName() {
        return "사실대조";
    }

    @Override
    public boolean supports(AnalysisContext context) {
        return openAiClient.isEnabled() && context.hasTranscript() && (perplexity.isEnabled() || serper.isEnabled());
    }

    @Override
    public Optional<String> unsupportedReason(AnalysisContext context) {
        if (openAiClient.isEnabled() && !perplexity.isEnabled() && !serper.isEnabled())
            return Optional.of("검색 API 키(Perplexity·Serper)가 없어 사실대조를 하지 못했습니다.");
        return Optional.empty();
    }

    @Override
    public List<RiskFinding> analyze(AnalysisContext context) {
        notice.remove();
        List<ContextLine> lines = context.reviewInput().segments().stream()
                .filter(s -> s.type() == TimelineEventType.SPEECH)
                .map(s -> new ContextLine(s.id(), s.startMs(), s.endMs(), s.text())).toList();
        var scope = openAiClient.currentScope();
        ContextReviewEngine.Model model = new ContextReviewEngine.Model() {
            @Override
            public <T> Optional<T> call(String system, String user, Class<T> type) {
                return openAiClient.withScope(scope, () -> openAiClient.completeAsJson(system, user, type));
            }
        };
        var limit = new Semaphore(properties.parallelism());
        java.util.concurrent.Executor executor = task -> pool().execute(() -> {
            limit.acquireUninterruptibly();
            try {
                task.run();
            } finally {
                limit.release();
            }
        });
        var engine = new FactCheckEngine(properties, contextProperties, model, perplexity, serper, executor);
        var result = engine.run(lines, context::checkActive);
        writeTrace(context, result);
        log.info("[fact-check] videoId={} AI {}회(실패 {}) 검색 {}회(실패 {}) 결과 {}건 상한초과 {}건",
                context.video().getId(), result.modelCalls(), result.failedCalls(), result.searchCalls(),
                result.failedSearches(), result.accepted().size(), result.skippedClaims());
        if (result.partial()) {
            StringBuilder sb = new StringBuilder("사실대조를 일부만 수행했습니다.");
            if (result.failedCalls() > 0) sb.append(" AI 호출 ").append(result.failedCalls()).append("건 실패.");
            if (result.failedSearches() > 0) sb.append(" 검색 ").append(result.failedSearches()).append("건 실패.");
            if (result.skippedClaims() > 0) sb.append(" 주장 상한을 넘은 ").append(result.skippedClaims()).append("건은 확인하지 못했습니다.");
            notice.set(sb.toString());
        }
        return result.accepted().stream().map(a -> toFinding(context, a)).toList();
    }

    @Override
    public Optional<String> consumeCoverageNotice(AnalysisContext context) {
        String value = notice.get();
        notice.remove();
        return Optional.ofNullable(value);
    }

    RiskFinding toFinding(AnalysisContext context, FactCheckEngine.Accepted a) {
        List<ContextLine> segs = a.segments();
        long start = segs.isEmpty() || segs.get(0).startMs() == null ? 0 : segs.get(0).startMs();
        long end = segs.stream().map(ContextLine::endMs).filter(java.util.Objects::nonNull).mapToLong(Long::longValue).max().orElse(start);
        var finding = RiskFinding.builder()
                .video(context.video())
                .eventType(TimelineEventType.SPEECH)
                .category(RiskCategory.FACT_ERROR)
                .source(EvidenceSource.SUBTITLE)
                .score(0.5)
                .startMs(start)
                .endMs(end)
                .text(segs.stream().map(l -> l.text().strip()).collect(Collectors.joining(" ")))
                .reason(a.reason() + "\n자료에서 확인된 내용: " + a.correction())
                .target(a.subject())
                .build();
        for (var s : a.sources()) {
            var ref = ReviewReference.of(s.item().title(), s.item().publisher(), s.item().url(), s.item().date(), s.item().snippet());
            ref.describeRelevance(s.quote());
            ref.classifyAs(classifier.classify(s.item()));
            finding.addReference(ref);
        }
        String anchor = segs.isEmpty() ? null : segs.get(0).id();
        var quotes = segs.stream().filter(l -> squash(l.text()).contains(squash(a.claimQuote())))
                .map(l -> new FindingSupport.Quote(l.id(), TimelineEventType.SPEECH,
                        l.startMs() == null ? 0 : l.startMs(), l.endMs() == null ? 0 : l.endMs(), a.claimQuote(), "PRIMARY"))
                .toList();
        if (anchor != null && !quotes.isEmpty()) finding.recordValidatedSupports(List.of(new FindingSupport(anchor, "CLAIM", quotes)));
        return finding;
    }

    private void writeTrace(AnalysisContext context, FactCheckEngine.Result result) {
        try {
            Long videoId = context.video().getId();
            var dir = storage.root().resolve("fact-traces").resolve(String.valueOf(videoId));
            Files.createDirectories(dir);
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("videoId", videoId);
            doc.put("createdAt", Instant.now().toString());
            doc.put("modelCalls", result.modelCalls());
            doc.put("failedCalls", result.failedCalls());
            doc.put("searchCalls", result.searchCalls());
            doc.put("failedSearches", result.failedSearches());
            doc.put("skippedClaims", result.skippedClaims());
            doc.put("invalidOutputs", result.invalidOutputs());
            doc.put("accepted", result.accepted().stream().map(a -> Map.of("claimId", a.claimId(), "subject", a.subject(),
                    "claim", a.claim(), "sources", a.sources().stream().map(s -> s.item().url()).toList())).toList());
            doc.put("trace", result.trace());
            Files.writeString(dir.resolve(System.currentTimeMillis() + ".json"), JSON.writeValueAsString(doc));
        } catch (Exception e) {
            log.warn("[fact-check] 내부 기록 저장 실패: {}", e.getMessage());
        }
    }

    private ExecutorService pool() {
        var p = pool;
        if (p == null) {
            synchronized (this) {
                if (pool == null) {
                    var count = new AtomicInteger();
                    pool = Executors.newFixedThreadPool(properties.parallelism() * 2, r -> {
                        Thread t = new Thread(r, "fact-check-" + count.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    });
                }
                p = pool;
            }
        }
        return p;
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        if (pool != null) pool.shutdownNow();
    }

    private static String squash(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }
}
