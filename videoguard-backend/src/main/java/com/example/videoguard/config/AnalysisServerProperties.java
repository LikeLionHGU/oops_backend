package com.example.videoguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "videoguard.analysis-server")
public record AnalysisServerProperties(
        String baseUrl,
        Duration timeout,
        Double ocrIntervalSec
) {
    public Duration timeoutOrDefault() {
        return timeout != null ? timeout : Duration.ofMinutes(10);
    }

    public double ocrIntervalOrDefault() {
        return ocrIntervalSec != null ? ocrIntervalSec : 2.0;
    }
}
