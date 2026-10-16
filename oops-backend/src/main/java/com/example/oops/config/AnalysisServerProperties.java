package com.example.oops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "oops.analysis-server")
public record AnalysisServerProperties(
        String baseUrl,
        Duration timeout,
        Double ocrIntervalSec,
        /**
         * OCR 이 글자를 찾은 장면을 화면 캡처로 저장할지. (2026-10 고도화, 기본 끔)
         * 켜면 {storage}/frames/{videoId}/ 에 jpg 가 쌓이고 리포트 카드에 캡처가 붙는다.
         * 자동 정리 대상이 아니라 영상 60여 개에 400MB 넘게 쌓였던 적이 있어 꺼 두었다.
         */
        Boolean saveFrames
) {
    public boolean saveFramesOrDefault() {
        return saveFrames != null && saveFrames;
    }

    public Duration timeoutOrDefault() {
        return timeout != null ? timeout : Duration.ofMinutes(10);
    }

    public double ocrIntervalOrDefault() {
        return ocrIntervalSec != null ? ocrIntervalSec : 2.0;
    }
}
