package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 발언(STT 대본) 리스크 분석기.
 *
 * 조롱 / 비하 / 과도한 일반화 / 민감 주제처럼 키워드로는 못 잡는 유형이 목표라
 * LLM 판정을 쓴다. 대본을 통째로 넣지 않고 창(window) 단위로 잘라 넣는데,
 * 앞뒤 문맥이 있어야 "조롱인지 자학인지" 를 구분할 수 있기 때문이다.
 *
 * API 키나 발언 입력이 없으면 supports=false로 실행하지 않는다. 수행 범위는 파이프라인이 기록한다.
 */
@Slf4j
@Component
public class SpeechReviewAnalyzer implements ContentAnalyzer {

    private final OpenAiClient openAiClient;
    private final int maxCandidates;
    private int evidenceLimit;
    private boolean sourceRelationsEnabled;
    private boolean finalReviewBasisEnabled;
    private boolean situationFirstEnabled;
    private boolean sourceUnderstandingEnabled;
    private boolean ruleUnionEnabled;
    void setRuleUnionEnabled(boolean enabled) {
        this.ruleUnionEnabled=enabled;
    }
    private SourceUnderstandingContract.Version sourceUnderstandingVersion=SourceUnderstandingContract.Version.ACTS_V1;
    void setSourceUnderstandingVersion(String value) {
        this.sourceUnderstandingVersion=SourceUnderstandingContract.Version.config(value);
    }
    void setSourceUnderstandingEnabled(boolean enabled) {
        this.sourceUnderstandingEnabled=enabled;
    }
    void setSituationFirstEnabled(boolean enabled) {
        this.situationFirstEnabled=enabled;
    }
    void setFinalReviewBasisEnabled(boolean enabled) {
        this.finalReviewBasisEnabled=enabled;
    }
    void setSourceRelationsEnabled(boolean enabled) {
        this.sourceRelationsEnabled = enabled;
    }
    void setEvidenceLimit(int limit) {
        if (limit < 0) throw new IllegalArgumentException("Evidence limit must be nonnegative");
        this.evidenceLimit = limit;
    }
    private VisualContextReviewer visualReviewer;
    private ReviewGuidelineLibrary guidelineLibrary;
    private com.example.oops.service.ReviewRequestTraceStore requestTraces;
    void setRequestTraces(com.example.oops.service.ReviewRequestTraceStore store) { this.requestTraces = store; }
    void setGuidelineLibrary(ReviewGuidelineLibrary library) { this.guidelineLibrary = library; }
    public SpeechReviewAnalyzer(OpenAiClient client) { this(client, 24); }
    public SpeechReviewAnalyzer(OpenAiClient client, int maxCandidates) {
        if (maxCandidates < 1 || maxCandidates > 200) throw new IllegalArgumentException("Candidate budget must be 1..200");
        this.openAiClient = client;
        this.maxCandidates = maxCandidates;
    }
    private final ThreadLocal<TextReviewEngine.Result> lastResult = new ThreadLocal<>();

    /** Single production injection path. Speech always uses candidate discovery and verification. */
    @org.springframework.beans.factory.annotation.Autowired
    public SpeechReviewAnalyzer(OpenAiClient client,
            com.example.oops.config.SpeechReviewProperties settings,
            VisualContextReviewer visualReviewer,
            ReviewGuidelineLibrary guidelineLibrary,
            com.example.oops.service.ReviewRequestTraceStore requestTraces) {
        this(client, settings.candidateReviewMaxCandidates());
        this.visualReviewer = visualReviewer;
        setEvidenceLimit(settings.candidateReviewEvidenceLimit());
        setSourceRelationsEnabled(settings.candidateSourceRelationsEnabled());
        setFinalReviewBasisEnabled(settings.finalReviewBasisEnabled());
        setSituationFirstEnabled(settings.situationFirstVerificationEnabled());
        setSourceUnderstandingEnabled(settings.sourceUnderstandingEnabled());
        setSourceUnderstandingVersion(settings.sourceUnderstandingContract());
        setRuleUnionEnabled(settings.ruleCandidateUnionEnabled());
        setGuidelineLibrary(guidelineLibrary);
        setRequestTraces(requestTraces);
        // Reject incompatible deployed flags at startup using the engine's single contract.
        runOptions();
    }

    @Override
    public String key() {
        return "speech-review";
    }

    @Override
    public String displayName() {
        return "발언 검토";
    }

    @Override
    public boolean supports(AnalysisContext context) {
        return openAiClient.isEnabled() && context.reviewInput().segments().stream()
                .anyMatch(s -> s.type() == TimelineEventType.SPEECH);
    }

    @Override
    public List<RiskFinding> analyze(AnalysisContext context) {
        lastResult.remove();
        TextReviewEngine.Result result = runCandidates(context);
        lastResult.set(result);
        log.info("[{}] videoId={} status={} findings={}", key(), context.video().getId(),
                result.status(), result.findings().size());
        return result.findings();
    }

    private TextReviewEngine.Result runCandidates(AnalysisContext context) {
        var options = runOptions();
        try (var trace = requestTraces == null ? null : requestTraces.begin(context.video().getId())) {
            var services = new CandidateReviewEngine.RunServices(visualReviewer, guidelineLibrary, trace);
            return CandidateReviewEngine.run(openAiClient, context, services, options);
        }
    }

    private CandidateReviewEngine.RunOptions runOptions() {
        var features = new CandidateReviewEngine.VerificationFeatures(finalReviewBasisEnabled,
                situationFirstEnabled, sourceUnderstandingEnabled, sourceUnderstandingVersion);
        return new CandidateReviewEngine.RunOptions(maxCandidates, evidenceLimit,
                sourceRelationsEnabled, features, ruleUnionEnabled);
    }

    @Override
    public java.util.Optional<String> consumeCoverageNotice(AnalysisContext context) {
        TextReviewEngine.Result result = lastResult.get();
        if (result == null) return java.util.Optional.empty();
        String notice = result.status() == AnalyzerStatus.SUCCESS ? null : result.notice();
        String guidelineNotice = guidelineLibrary == null ? null : guidelineLibrary.unavailableNotice(TimelineEventType.SPEECH).orElse(null);
        return java.util.Optional.ofNullable(guidelineNotice == null ? notice : notice == null ? guidelineNotice : notice + " " + guidelineNotice);
    }

    @Override
    public java.util.Optional<String> consumeContextLimitNotice(AnalysisContext context) {
        TextReviewEngine.Result result = lastResult.get();
        return result != null && result.status() == AnalyzerStatus.SUCCESS
                ? java.util.Optional.ofNullable(result.notice()) : java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<TextReviewEngine.Result> consumeReviewResult(AnalysisContext context) {
        TextReviewEngine.Result result = lastResult.get();
        lastResult.remove();
        return java.util.Optional.ofNullable(result);
    }

    static final java.util.Set<RiskCategory> ALLOWED_CATEGORIES = java.util.EnumSet.of(
            RiskCategory.UNFAMILIAR_CONTEXT, RiskCategory.BELITTLEMENT, RiskCategory.MOCKERY,
            RiskCategory.GENERALIZATION, RiskCategory.SENSITIVE_TOPIC, RiskCategory.DISCRIMINATION,
            RiskCategory.HATE_SPEECH, RiskCategory.PRIVACY, RiskCategory.MISINFORMATION,
            RiskCategory.PROFANITY, RiskCategory.VIOLENCE, RiskCategory.SEXUAL,
            RiskCategory.STRONG_NEGATIVE_REVIEW, RiskCategory.GRAPHIC_METAPHOR);

}
