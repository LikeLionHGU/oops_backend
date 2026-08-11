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
        Duration timeout
) {
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
