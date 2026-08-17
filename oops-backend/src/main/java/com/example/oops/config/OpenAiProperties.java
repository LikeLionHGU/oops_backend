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
}
