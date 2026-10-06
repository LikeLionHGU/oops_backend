package com.example.oops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 분석 동시성과 대기열을 코드 변경 없이 조정할 수 있는 설정. */
@ConfigurationProperties(prefix = "oops.analysis-executor")
public record AnalysisExecutorProperties(
        Integer corePoolSize,
        Integer maxPoolSize,
        Integer queueCapacity
) {
    public int corePoolSizeOrDefault() {
        return corePoolSize == null ? 1 : Math.max(1, corePoolSize);
    }

    public int maxPoolSizeOrDefault() {
        return maxPoolSize == null ? 2 : Math.max(corePoolSizeOrDefault(), maxPoolSize);
    }

    public int queueCapacityOrDefault() {
        return queueCapacity == null ? 50 : Math.max(0, queueCapacity);
    }
}
