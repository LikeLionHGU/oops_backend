package com.example.oops.auth;

import com.example.oops.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인 상태 확인용 API.
 *   GET  /api/v1/auth/me     로그인했는지와 사용자 정보. 로그인 안 했으면 loggedIn=false (401 아님)
 *   GET  /api/v1/auth/csrf   상태를 바꾸는 요청에 넣을 CSRF 토큰 (헤더 이름 + 값)
 *   POST /api/v1/auth/logout 로그아웃 (SecurityConfig, CSRF 토큰 필요) → 204
 *   로그인 시작: 브라우저를 {백엔드}/oauth2/authorization/google 로 이동
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final CurrentUser currentUser;
    private final AuthProperties properties;

    public record Me(boolean loggedIn, boolean authEnabled, String loginUrl, User user) {
        public record User(String id, String displayName, String email) {}
    }

    public record Csrf(String headerName, String token) {}

    @GetMapping("/me")
    public ApiResponse<Me> me() {
        var user = currentUser.get()
                .map(u -> new Me.User(String.valueOf(u.userId()), u.displayName(), u.email())).orElse(null);
        return ApiResponse.ok(new Me(user != null, properties.isEnabled(), SecurityConfig.LOGIN_PATH, user));
    }

    @GetMapping("/csrf")
    public ApiResponse<Csrf> csrf(HttpServletRequest request) {
        Object attr = request.getAttribute(CsrfToken.class.getName());
        if (!(attr instanceof CsrfToken token)) return ApiResponse.ok(new Csrf(null, null));   // 로그인을 끈 로컬
        return ApiResponse.ok(new Csrf(token.getHeaderName(), token.getToken()));
    }
}
