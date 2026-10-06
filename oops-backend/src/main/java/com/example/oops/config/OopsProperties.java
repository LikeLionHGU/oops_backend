package com.example.oops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "oops")
public record OopsProperties(Storage storage, Analysis analysis, RateLimit rateLimit) {

    public RateLimit rateLimitOrDefault() {
        return rateLimit == null ? new RateLimit(null, null) : rateLimit;
    }

    /**
     * 인증이 아직 없는 데모 배포 환경에서 반복 요청을 제한한다.
     * 서버를 여러 대로 늘릴 때는 Redis 기반 제한기로 교체한다.
     */
    public record RateLimit(Integer uploadsPerMinute, Integer retriesPerMinute) {

        public int uploadsPerMinuteOrDefault() {
            return uploadsPerMinute == null ? 10 : Math.max(0, uploadsPerMinute);
        }

        public int retriesPerMinuteOrDefault() {
            return retriesPerMinute == null ? 5 : Math.max(0, retriesPerMinute);
        }
    }

    public record Storage(String location, Integer retentionDays, Integer sourceRetentionHours) {

        /** 0 이하면 자동 정리를 하지 않는다. */
        public int retentionDaysOrDefault() {
            return retentionDays == null ? 0 : retentionDays;
        }

        /** 원본만 지울 보관 시간. 0 이하면 원본을 자동 삭제하지 않는다. */
        public int sourceRetentionHoursOrDefault() {
            return sourceRetentionHours == null ? 0 : sourceRetentionHours;
        }
    }

    /** enabled-analyzers 에 적힌 키를 가진 분석기만 파이프라인에서 실행된다. */
    public record Analysis(List<String> enabledAnalyzers) {}
}
