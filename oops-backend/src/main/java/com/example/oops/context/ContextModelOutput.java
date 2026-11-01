package com.example.oops.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 맥락 확인 3단계의 AI 출력 형태. (redesign-v3, docs/redesign-v3.md 3절)
 *
 * AI 출력은 그대로 믿지 않는다. {@link ContextReviewEngine} 이 구간 ID·인용·코드를 모두 검사한다.
 * 목록이 null 로 와도 빈 목록으로 다룬다.
 */
public final class ContextModelOutput {

    private ContextModelOutput() {
    }

    static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values.stream().filter(java.util.Objects::nonNull).toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Evidence(String segmentId, String quote) {}

    // ── 1. 후보 탐색 ──
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Discovery(List<String> checkedCriterionIds, List<DiscoveryCandidate> candidates) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DiscoveryCandidate(String criterionId, List<String> coreSegmentIds, String reason) {}

    // ── 2. 독립 점검 ──
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Gate(List<GateResult> results) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GateResult(String candidateId, String status, String reason, List<Evidence> evidence,
                             List<String> allowedJudgments, List<String> stoppedJudgments,
                             List<MissingInformation> missingInformation, Boolean needsExpansion) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MissingInformation(String what, Boolean availableInTranscript) {}

    // ── 3. 상세 검토 ──
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Review(List<ReviewResult> reviews) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReviewResult(String candidateId, String decision, String reason, String interpretationLimit,
                               List<Evidence> evidence, List<String> usedJudgments,
                               List<AdditionalReview> additionalReviews) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AdditionalReview(String additionalId, String effect, String finding) {}
}
