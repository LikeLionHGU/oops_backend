package com.example.oops.context;

import com.example.oops.analyzer.AnalysisContext;
import com.example.oops.analyzer.ContentAnalyzer;
import com.example.oops.analyzer.ReviewInput;
import com.example.oops.client.OpenAiClient;
import com.example.oops.context.ContextReviewEngine.Accepted;
import com.example.oops.context.LineContextBuilder.ContextLine;
import com.example.oops.context.criteria.CriteriaSource;
import com.example.oops.domain.EvidenceSource;
import com.example.oops.domain.FindingSupport;
import com.example.oops.domain.RiskCategory;
import com.example.oops.domain.RiskFinding;
import com.example.oops.domain.TimelineEventType;
import com.example.oops.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 맥락 확인 분석기 — 21개 기준, 3단계. (redesign-v3, key: context-review)
 *
 * 1. 기준을 CriteriaSource 에서 한 번 읽어 이 실행에 고정한다. 못 읽으면 예외 → 파이프라인이 FAILED 로 기록한다.
 * 2. 전사 줄로 ContextReviewEngine 을 돌린다.
 * 3. 상세 검토까지 통과하고 서버 검증을 마친 결과만 카드(RiskFinding)로 만든다.
 *    제외·중단·정보 부족·무효 출력은 내부 기록(trace 파일)에만 남기고 사용자에게 보여주지 않는다.
 *
 * 내부 기록: {storage}/context-traces/{videoId}/{epochMs}.json (텍스트라 원본 삭제 후에도 남는다)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextCriteriaAnalyzer implements ContentAnalyzer {

    public static final String KEY = "context-review";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final double SCORE = 0.5;   // 점수를 만들지 않는다. 정렬용 고정값

    private final CriteriaSource criteriaSource;
    private final OpenAiClient openAiClient;
    private final ContextProperties properties;
    private final StorageService storage;

    private final ThreadLocal<String> notice = new ThreadLocal<>();

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String displayName() {
        return "맥락 기준 검토";
    }

    @Override
    public boolean supports(AnalysisContext context) {
        return openAiClient.isEnabled() && context.hasTranscript();
    }

    @Override
    public List<RiskFinding> analyze(AnalysisContext context) {
        notice.remove();
        var criteria = criteriaSource.load();
        List<ContextLine> lines = context.reviewInput().segments().stream()
                .filter(s -> s.type() == TimelineEventType.SPEECH)
                .map(s -> new ContextLine(s.id(), s.startMs(), s.endMs(), s.text()))
                .toList();
        var engine = new ContextReviewEngine(properties, openAiClient::completeAsJson);
        var result = engine.run(lines, criteria, context::checkActive);

        writeTrace(context, lines.size(), result);
        log.info("[context-review] videoId={} 호출 {}회(실패 {}) 결과 {}건 무효출력 {}건 상한초과 {}건",
                context.video().getId(), result.modelCalls(), result.failedCalls(), result.accepted().size(),
                result.invalidOutputs(), result.skippedCandidates());
        if (result.partial()) {
            StringBuilder sb = new StringBuilder("맥락 기준 검토를 일부만 수행했습니다.");
            if (result.failedCalls() > 0) sb.append(" AI 호출 ").append(result.failedCalls()).append("건 실패.");
            if (result.skippedCandidates() > 0) sb.append(" 후보 상한을 넘은 ").append(result.skippedCandidates()).append("건은 확인하지 못했습니다.");
            notice.set(sb.toString());
        }
        return result.accepted().stream().map(a -> toFinding(context, a, result.criteriaVersion())).toList();
    }

    @Override
    public Optional<String> consumeCoverageNotice(AnalysisContext context) {
        String value = notice.get();
        notice.remove();
        return Optional.ofNullable(value);
    }

    static RiskFinding toFinding(AnalysisContext context, Accepted a, String criteriaVersion) {
        List<ContextLine> core = a.core();
        long start = core.get(0).startMs() == null ? 0 : core.get(0).startMs();
        long end = core.stream().map(ContextLine::endMs).filter(java.util.Objects::nonNull).mapToLong(Long::longValue)
                .max().orElse(start);
        String reason = a.reason();
        if (a.interpretationLimit() != null) reason = reason + "\n해석 한계: " + a.interpretationLimit();
        var finding = RiskFinding.builder()
                .video(context.video())
                .eventType(TimelineEventType.SPEECH)
                .category(RiskCategory.CONTEXT_CRITERION)
                .source(EvidenceSource.SUBTITLE)
                .score(SCORE)
                .startMs(start)
                .endMs(end)
                .text(core.stream().map(l -> l.text().strip()).collect(Collectors.joining(" ")))
                .reason(reason)
                .build();
        finding.recordCriterion(a.criterion().id(), a.criterion().common().title(), criteriaVersion);
        var quotes = a.evidence().stream().map(e -> new FindingSupport.Quote(e.segmentId(), TimelineEventType.SPEECH,
                e.startMs(), e.endMs(), e.quote(), e.role())).toList();
        finding.recordValidatedSupports(List.of(new FindingSupport(core.get(0).id(), null, quotes)));
        return finding;
    }

    private void writeTrace(AnalysisContext context, int lineCount, ContextReviewEngine.Result result) {
        try {
            Long videoId = context.video().getId();
            Path dir = storage.root().resolve("context-traces").resolve(String.valueOf(videoId));
            Files.createDirectories(dir);
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("videoId", videoId);
            doc.put("createdAt", Instant.now().toString());
            doc.put("criteriaVersion", result.criteriaVersion());
            doc.put("transcriptLines", lineCount);
            doc.put("modelCalls", result.modelCalls());
            doc.put("failedCalls", result.failedCalls());
            doc.put("invalidOutputs", result.invalidOutputs());
            doc.put("skippedCandidates", result.skippedCandidates());
            doc.put("accepted", result.accepted().stream().map(a -> Map.of(
                    "candidateId", a.candidateId(), "criterionId", a.criterion().id(),
                    "coreSegmentIds", a.core().stream().map(ContextLine::id).toList(),
                    "allowedJudgments", a.allowedJudgments(), "stoppedJudgments", a.stoppedJudgments(),
                    "expanded", a.expanded())).toList());
            doc.put("trace", result.trace());
            Files.writeString(dir.resolve(System.currentTimeMillis() + ".json"), JSON.writeValueAsString(doc));
        } catch (Exception e) {
            // 내부 기록을 못 남겨도 분석 결과는 살린다. 결과를 "문제없음"으로 바꾸지는 않는다.
            log.warn("[context-review] 내부 기록 저장 실패: {}", e.getMessage());
        }
    }
}
