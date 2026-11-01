package com.example.oops.context;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 맥락 확인 설정. (redesign-v3, docs/redesign-v3.md 2절·5절)
 *
 * 숫자는 출발점이다. 평가 세트로 비교해 조정한다.
 */
@ConfigurationProperties(prefix = "oops.context")
public record ContextProperties(
        /** 후보 탐색 대상 한 묶음의 최대 줄 수 */
        @DefaultValue("20") int discoveryLines,
        /** 후보 탐색 대상 한 묶음의 최대 길이(ms). 줄 수와 둘 중 먼저 닿는 쪽에서 끊는다 */
        @DefaultValue("60000") long discoveryMaxMs,
        /** 기본 문맥: 앞뒤 각 줄 수 */
        @DefaultValue("5") int contextLines,
        /** 기본 문맥: 앞뒤 각 최소 길이(ms). 줄 수가 모자라도 이 길이까지는 채운다 */
        @DefaultValue("15000") long contextMinMs,
        /** 기본 문맥: 앞뒤 각 최대 길이(ms) */
        @DefaultValue("30000") long contextMaxMs,
        /** 1회 확대: 앞뒤 각 줄 수 */
        @DefaultValue("10") int expandedLines,
        /** 1회 확대: 앞뒤 각 최대 길이(ms) */
        @DefaultValue("60000") long expandedMaxMs,
        /** 이보다 긴 공백 너머의 줄은 문맥에 붙이지 않는다 */
        @DefaultValue("5000") long silenceGapMs,
        /** 독립 점검·상세 검토에서 같은 기준 후보를 한 번에 보내는 수 */
        @DefaultValue("5") int batchSize,
        /** 영상 하나에서 독립 점검까지 보낼 후보 상한. 넘으면 PARTIAL 로 알린다 */
        @DefaultValue("80") int maxCandidates,
        /** 영상 하나 안에서 동시에 보내는 AI 호출 수. 서버 전체 상한은 oops.openai.max-concurrent */
        @DefaultValue("4") int parallelism) {

    public ContextProperties {
        if (discoveryLines < 1 || contextLines < 0 || expandedLines < contextLines
                || discoveryMaxMs < 1 || contextMinMs < 0 || contextMaxMs < contextMinMs
                || expandedMaxMs < contextMaxMs || silenceGapMs < 0 || batchSize < 1 || maxCandidates < 1
                || parallelism < 1 || parallelism > 32) {
            throw new IllegalArgumentException("oops.context 설정값이 올바르지 않습니다.");
        }
    }

    public static ContextProperties defaults() {
        return new ContextProperties(20, 60_000, 5, 15_000, 30_000, 10, 60_000, 5_000, 5, 80, 4);
    }
}
