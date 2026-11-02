package com.example.oops.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 로그인 설정 (oops.auth).
 *
 * enabled=false 이면 로그인 없이 모든 요청을 "로컬 개발자" 한 명으로 처리한다(로컬 시험·smoke-test 용).
 * 이때는 영상 주인 검사도 하지 않는다. 서버에 올릴 때는 반드시 true.
 */
@ConfigurationProperties(prefix = "oops.auth")
public record AuthProperties(
        Boolean enabled,
        Google google,
        /** 로그인 성공 후 돌아갈 프론트 주소. 실패하면 여기에 ?login=failed 를 붙인다. */
        String successRedirect,
        /** 운영 지표(/api/v1/metrics)를 볼 수 있는 이메일 */
        List<String> adminEmails) {

    public record Google(String clientId, String clientSecret) {
        public boolean isConfigured() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }
    }

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public Google googleOrEmpty() {
        return google == null ? new Google(null, null) : google;
    }

    public String successRedirectOrDefault() {
        return successRedirect == null || successRedirect.isBlank() ? "/" : successRedirect;
    }

    public List<String> adminEmailsOrEmpty() {
        return adminEmails == null ? List.of() : adminEmails;
    }
}
