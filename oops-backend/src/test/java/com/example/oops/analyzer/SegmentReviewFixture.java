package com.example.oops.analyzer;

import com.example.oops.client.OpenAiClient;
import com.example.oops.domain.AnalyzerStatus;
import com.example.oops.domain.RiskFinding;
import com.example.oops.domain.TimelineEventType;
import java.util.List;
import java.util.Optional;

/** Mock-only adapter for shared segment/evidence contracts, NOT the production speech pipeline. */
public final class SegmentReviewFixture implements ContentAnalyzer {
    private final OpenAiClient client;
    private TextReviewEngine.Result result;

    public SegmentReviewFixture(OpenAiClient client) { this.client = client; }
    @Override public String key() { return "speech-review"; }
    @Override public String displayName() { return "Synthetic segment contract"; }
    @Override public boolean supports(AnalysisContext context) {
        return client.isEnabled() && context.reviewInput().segments().stream()
                .anyMatch(s -> s.type() == TimelineEventType.SPEECH);
    }
    @Override public List<RiskFinding> analyze(AnalysisContext context) {
        result = null;
        result = TextReviewEngine.run(client, context, TimelineEventType.SPEECH, key(),
                "Synthetic segment contract", SpeechReviewAnalyzer.ALLOWED_CATEGORIES, 3);
        return result.findings();
    }
    @Override public Optional<TextReviewEngine.Result> consumeReviewResult(AnalysisContext context) {
        var previous = result;
        result = null;
        return Optional.ofNullable(previous);
    }
    @Override public Optional<String> consumeCoverageNotice(AnalysisContext context) {
        return result != null && result.status() != AnalyzerStatus.SUCCESS
                ? Optional.ofNullable(result.notice()) : Optional.empty();
    }
    @Override public Optional<String> consumeContextLimitNotice(AnalysisContext context) {
        return result != null && result.status() == AnalyzerStatus.SUCCESS
                ? Optional.ofNullable(result.notice()) : Optional.empty();
    }
}
