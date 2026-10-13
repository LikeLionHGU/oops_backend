package com.example.oops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "oops")
public record OopsProperties(Storage storage, Analysis analysis, RateLimit rateLimit) {

    public RateLimit rateLimitOrDefault() {
        return rateLimit == null ? new RateLimit(null, null, null) : rateLimit;
    }

    /**
     * 인증이 아직 없는 데모 배포 환경에서 반복 요청을 제한한다.
     * 서버를 여러 대로 늘릴 때는 Redis 기반 제한기로 교체한다.
     *
     * trustedProxies: X-Forwarded-For / X-Real-IP 를 신뢰할 수 있는 리버스 프록시의
     *   주소/CIDR 목록. 직접 연결한 피어(getRemoteAddr)가 이 목록에 들 때만
     *   forwarded 헤더로 클라이언트 IP 를 판정한다.
     *   비어 있으면(기본) forwarded 헤더를 전혀 신뢰하지 않고 getRemoteAddr 만 쓴다.
     *   프록시 뒤에 두지 않은 배포에서 클라이언트가 헤더를 위조해
     *   IP 기준 레이트리밋을 우회하는 것을 막기 위한 fail-closed 기본값이다.
     */
    public record RateLimit(Integer uploadsPerMinute, Integer retriesPerMinute,
                            List<String> trustedProxies) {

        public int uploadsPerMinuteOrDefault() {
            return uploadsPerMinute == null ? 10 : Math.max(0, uploadsPerMinute);
        }

        public int retriesPerMinuteOrDefault() {
            return retriesPerMinute == null ? 5 : Math.max(0, retriesPerMinute);
        }

        public List<String> trustedProxiesOrDefault() {
            return trustedProxies == null ? List.of() : trustedProxies;
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
