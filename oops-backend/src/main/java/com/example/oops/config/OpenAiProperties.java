package com.example.oops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "oops.openai")
public record OpenAiProperties(
        String apiKey,
        /**
         * 조직 ID (org-...). 지원 크레딧처럼 특정 Organization 에 잔액이 붙어 있고,
         * 계정이 여러 Organization 에 속해 있을 때 어느 쪽으로 과금할지 지정한다.
         * sk-proj-... 형태의 프로젝트 키를 쓴다면 비워둬도 된다.
         */
        String organization,
        /** 프로젝트 ID (proj_...). 보통 비워둔다. */
        String project,
        String baseUrl,
        String model,
        Duration timeout,

        /**
         * 분당 보낼 요청 수. 계정 한도(RPM)에 맞춘다.
         *
         * 이 값이 계정 한도보다 크면 429 가 나고, OpenAI 는 벌칙으로
         * 수십 분 뒤에 다시 오라고 답한다. 그러면 그 영상 분석은 사실상 끝난다.
         * 그래서 **낮게 잡는 편이 안전하다.** 느린 것과 실패하는 것은 다르다.
         *
         * 실제 한도는 첫 응답 헤더(x-ratelimit-limit-requests)로 확인되고,
         * 그때 이 값이 자동으로 맞춰진다. 여기 적는 건 그 전까지 쓸 초기값이다.
         *
         * 계정 한도 확인: https://platform.openai.com/settings/organization/limits
         */
        Integer requestsPerMinute,

        /**
         * 하루에 보낼 수 있는 요청 수(RPD). 몇 편이나 돌릴 수 있는지 로그에 찍는 데만 쓴다.
         * 속도 제어에는 쓰지 않는다. 하루 한도는 헤더로 안 오기 때문에 알 방법이 없다.
         *
         * Tier 1 의 gpt-4o-mini 는 10,000 이다. 무료 계정은 200 이다.
         */
        Integer requestsPerDay,

        /** 토큰 단가. 비용을 로그로 확인하는 용도다. */
        Pricing pricing
) {
    /**
     * 1M 토큰당 단가(USD).
     *
     * 기본값은 gpt-4o-mini 기준이다. 코드에 박아두지 않고 설정으로 뺀 이유는,
     * OpenAI 가 가격을 바꾸면 로그 숫자가 조용히 틀리기 시작하는데
     * 틀린 줄도 모르게 되기 때문이다. 모델을 바꿀 때도 여기만 고치면 된다.
     */
    public record Pricing(Double inputPer1m,
                          Double cachedInputPer1m,
                          Double outputPer1m,
                          /** 음성 인식은 분당 과금이다 */
                          Double sttPerMinute,
                          /** 원화로 환산해 보여줄 때 쓰는 대략적인 환율 */
                          Double usdToKrw) {

        // record 컴포넌트와 같은 이름을 쓰면 반환 타입(Double)까지 같아야 해서
        // 기본값 처리를 넣을 수 없다. 그래서 이름을 따로 둔다.
        public double inputUsd()        { return inputPer1m       == null ? 0.15  : inputPer1m; }
        public double cachedInputUsd()  { return cachedInputPer1m == null ? 0.075 : cachedInputPer1m; }
        public double outputUsd()       { return outputPer1m      == null ? 0.60  : outputPer1m; }
        public double sttUsdPerMinute() { return sttPerMinute     == null ? 0.006 : sttPerMinute; }
        public double krwRate()         { return usdToKrw         == null ? 1400  : usdToKrw; }
    }

    /** 설정이 비어 있어도 기본 단가로 계산한다. */
    public Pricing pricing() {
        return pricing != null ? pricing : new Pricing(null, null, null, null, null);
    }
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean hasOrganization() {
        return organization != null && !organization.isBlank();
    }

    public boolean hasProject() {
        return project != null && !project.isBlank();
    }

    public String baseUrlOrDefault() {
        return baseUrl != null ? baseUrl : "https://api.openai.com/v1";
    }

    public String modelOrDefault() {
        return model != null ? model : "gpt-4o-mini";
    }

    public Duration timeoutOrDefault() {
        return timeout != null ? timeout : Duration.ofSeconds(90);
    }

    /** 하루 요청 한도. Tier 1 의 gpt-4o-mini 는 10,000, 무료 계정은 200 */
    public int requestsPerDayOrDefault() {
        return requestsPerDay == null || requestsPerDay <= 0 ? 10_000 : requestsPerDay;
    }

    /**
     * 시작할 때 쓸 분당 요청 수. 기본 10.
     *
     * **낮게 시작하는 게 중요하다.** 실제 한도보다 빠르게 보내면 429 가 나고,
     * OpenAI 는 벌칙으로 수십 분 뒤에 오라고 답한다. 그 영상은 결과가 통째로 빈다.
     *
     * 계정마다 한도가 크게 다르다.
     *   무료      분당 3건
     *   Tier 1    분당 500건   ($5 이상 구매)
     *
     * 어느 쪽인지 모르는 상태로 500 부터 시작하면 무료 계정에서 즉시 막힌다.
     * 반대로 10 으로 시작해서 손해 보는 건 첫 호출까지의 몇 초뿐이고,
     * 첫 응답 헤더(x-ratelimit-limit-requests)를 보면 알아서 올라간다.
     */
    public int requestsPerMinuteOrDefault() {
        return requestsPerMinute == null || requestsPerMinute <= 0 ? 10 : requestsPerMinute;
    }
}
