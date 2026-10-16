package com.example.oops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 네이버 검색 API 설정.
 * https://developers.naver.com 에서 애플리케이션을 등록하면 무료로 발급받는다.
 *
 * 2026-10: 기본으로 끈다(enabled=false). 뉴스 기사 본문·요약을 서비스에 쓰는 것에
 * 저작권·이용약관 문제가 있을 수 있어서다. 키가 있어도 enabled=true 가 아니면 호출하지 않는다.
 */
@ConfigurationProperties(prefix = "oops.naver")
public record NaverProperties(Boolean enabled, String clientId, String clientSecret) {

    public boolean isConfigured() {
        return Boolean.TRUE.equals(enabled)
                && clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }
}
