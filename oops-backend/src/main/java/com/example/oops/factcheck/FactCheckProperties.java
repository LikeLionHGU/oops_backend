package com.example.oops.factcheck;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 사실대조 설정. (redesign-v3, docs/redesign-v3.md 6절) */
@ConfigurationProperties(prefix = "oops.fact-check")
public record FactCheckProperties(
        /** 영상당 검색까지 갈 사실 주장 상한. 넘으면 PARTIAL */
        @DefaultValue("12") int maxClaims,
        /** 검색 한 번에 받는 자료 수 */
        @DefaultValue("5") int resultsPerSearch,
        /** 주장 여러 개를 동시에 확인하는 수 */
        @DefaultValue("4") int parallelism) {

    public FactCheckProperties {
        if (maxClaims < 1 || resultsPerSearch < 1 || resultsPerSearch > 20 || parallelism < 1 || parallelism > 32)
            throw new IllegalArgumentException("oops.fact-check 설정값이 올바르지 않습니다.");
    }

    public static FactCheckProperties defaults() {
        return new FactCheckProperties(12, 5, 4);
    }
}
