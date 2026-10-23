package com.example.oops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "oops.analysis-server")
public record AnalysisServerProperties(
        String baseUrl,
        Duration timeout,
        Double ocrIntervalSec,
        Duration ocrTimeout
) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public AnalysisServerProperties {
        if (ocrTimeout != null && ocrTimeout.isNegative()) {
            throw new IllegalArgumentException("OCR timeout must be zero or positive");
        }
    }

    public AnalysisServerProperties(String baseUrl, Duration timeout, Double ocrIntervalSec) {
        this(baseUrl, timeout, ocrIntervalSec, null);
    }

    public Duration ocrTimeoutOrDefault() {
        return ocrTimeout != null ? ocrTimeout : timeoutOrDefault();
    }
    public Duration timeoutOrDefault() {
        return timeout != null ? timeout : Duration.ofMinutes(10);
    }

    public double ocrIntervalOrDefault() {
        return ocrIntervalSec != null ? ocrIntervalSec : 2.0;
    }
}
