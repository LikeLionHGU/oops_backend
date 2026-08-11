package com.example.oops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 네이버 검색 API 설정.
 * https://developers.naver.com 에서 애플리케이션을 등록하면 무료로 발급받는다.
 */
@ConfigurationProperties(prefix = "oops.naver")
public record NaverProperties(String clientId, String clientSecret) {

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }
}
