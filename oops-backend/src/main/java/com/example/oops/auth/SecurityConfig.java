package com.example.oops.auth;

import com.example.oops.config.CorsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 로그인·권한. (구글 OAuth2/OIDC + 서버 세션 쿠키)
 *
 * 흐름
 *   1. 프론트가 브라우저를 {백엔드}/oauth2/authorization/google 로 보낸다.
 *   2. 구글 로그인 → {백엔드}/login/oauth2/code/google 로 돌아오면 회원을 찾거나 만들고 세션을 만든다.
 *   3. oops.auth.success-redirect(프론트 주소)로 돌려보낸다. 이후 요청은 세션 쿠키(JSESSIONID)로 인증된다.
 *   4. 상태를 바꾸는 요청(POST·PUT·DELETE)은 GET /api/v1/auth/csrf 로 받은 토큰을 X-CSRF-TOKEN 헤더에 넣는다(헤더 이름도 응답에 있다).
 *
 * oops.auth.enabled=false 이면 로그인 없이 모든 요청을 "로컬 개발자"로 처리하고 CSRF 도 끈다(로컬 시험용).
 * 이때도 이 컴퓨터(localhost)에서 직접 온 요청만 받는다. 서버에 꺼진 채로 올라가도 외부에는 열리지 않는다.
 */
@Slf4j
@Configuration
public class SecurityConfig {

    public static final String LOGIN_PATH = "/oauth2/authorization/google";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthProperties props, AppUserService users,
                                            ObjectProvider<ClientRegistrationRepository> registrations) throws Exception {
        http.cors(c -> { });
        http.headers(h -> h.frameOptions(f -> f.sameOrigin()));   // 로컬 H2 콘솔
        http.exceptionHandling(e -> e.authenticationEntryPoint(SecurityConfig::unauthorized));

        if (!props.isEnabled()) {
            log.warn("[auth] 로그인이 꺼져 있습니다(oops.auth.enabled=false). 모든 요청을 '로컬 개발자'로 처리합니다. 서버에서는 켜세요.");
            http.csrf(AbstractHttpConfigurer::disable);
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
            http.addFilterBefore(new LocalDeveloperFilter(users), AnonymousAuthenticationFilter.class);
            return http.build();
        }

        SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
        http.securityContext(s -> s.securityContextRepository(contexts));
        http.csrf(c -> c.csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers("/ws/**"));
        http.authorizeHttpRequests(a -> a
                .requestMatchers("/health", "/api/v1/health", "/api/v1/auth/**", "/oauth2/**", "/login/oauth2/**", "/error").permitAll()
                .requestMatchers("/api/**", "/ws/**").authenticated()
                .anyRequest().permitAll());
        http.oauth2Login(o -> o
                .clientRegistrationRepository(registrations.getObject())
                .authorizedClientRepository(new NoStoreAuthorizedClientRepository())
                .successHandler((req, res, auth) -> onLogin(req, res, auth, props, users, contexts))
                .failureHandler((req, res, ex) -> {
                    log.warn("[auth] 구글 로그인 실패: {}", ex.getMessage());
                    res.sendRedirect(withQuery(props.successRedirectOrDefault(), "login=failed"));
                }));
        http.logout(l -> l.logoutUrl("/api/v1/auth/logout")
                .logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpServletResponse.SC_NO_CONTENT)));
        return http.build();
    }

    /** 구글 키는 oops.auth.google 에서 읽는다. 로그인을 켰는데 키가 없으면 서버를 띄우지 않는다. */
    @Bean
    ClientRegistrationRepository clientRegistrationRepository(AuthProperties props) {
        var google = props.googleOrEmpty();
        if (!google.isConfigured()) {
            if (props.isEnabled())
                throw new IllegalStateException("로그인이 켜져 있지만 구글 키가 없습니다. GOOGLE_CLIENT_ID·GOOGLE_CLIENT_SECRET 을 넣거나 oops.auth.enabled=false 로 실행하세요.");
            // 로그인을 끈 로컬에서는 쓰이지 않는 자리표시 값
            return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                    .clientId("disabled").clientSecret("disabled").build());
        }
        return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(google.clientId()).clientSecret(google.clientSecret()).build());
    }

    /** 스프링 시큐리티 필터가 쓰는 CORS 설정. 쿠키를 주고받으므로 Origin 은 목록에 있는 것만 허용한다. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties cors) {
        var config = new CorsConfiguration();
        config.setAllowedOriginPatterns(cors.originsOrDefault());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Content-Range", "Accept-Ranges", "Content-Length", "Retry-After", "X-Request-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(86400L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        source.registerCorsConfiguration("/ws/**", config);
        return source;
    }

    private static void onLogin(HttpServletRequest req, HttpServletResponse res, Authentication auth, AuthProperties props,
                                AppUserService users, SecurityContextRepository contexts) throws IOException {
        if (!(auth instanceof OAuth2AuthenticationToken token) || !(token.getPrincipal() instanceof OidcUser oidc)) {
            res.sendRedirect(withQuery(props.successRedirectOrDefault(), "login=failed"));
            return;
        }
        AppUser user = users.loginWithGoogle(oidc.getIssuer() == null ? null : oidc.getIssuer().toString(),
                oidc.getSubject(), oidc.getFullName(), oidc.getEmail(), Boolean.TRUE.equals(oidc.getEmailVerified()));
        if (!user.isActive()) {
            SecurityContextHolder.clearContext();
            var session = req.getSession(false);
            if (session != null) session.invalidate();
            res.sendRedirect(withQuery(props.successRedirectOrDefault(), "login=blocked"));
            return;
        }
        // 세션에는 구글 토큰·프로필 전체가 아니라 회원 ID 와 표시 정보만 남긴다.
        var principal = new AuthenticatedUser(user.getId(), user.getDisplayName(), user.getEmail(), false);
        var authenticated = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticated);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, req, res);
        log.info("[auth] 로그인 userId={}", user.getId());
        res.sendRedirect(props.successRedirectOrDefault());
    }

    static void unauthorized(HttpServletRequest req, HttpServletResponse res, Exception e) throws IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write("{\"success\":false,\"data\":null,\"error\":{\"code\":\"UNAUTHORIZED\","
                + "\"message\":\"로그인이 필요합니다.\",\"details\":{\"loginUrl\":\"" + LOGIN_PATH + "\"}}}");
    }

    static String withQuery(String url, String query) {
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    /** 로그인을 끈 환경: 모든 요청을 로컬 개발자 한 명으로 인증한다. */
    static final class LocalDeveloperFilter extends OncePerRequestFilter {
        private final AppUserService users;

        LocalDeveloperFilter(AppUserService users) {
            this.users = users;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws jakarta.servlet.ServletException, IOException {
            // 로그인을 끈 상태는 내 PC 에서만 쓴다. 서버에서 설정이 빠져 꺼진 채로 떠도 외부 요청은 막는다.
            if (!fromThisMachine(req)) {
                log.warn("[auth] 로그인이 꺼진 서버에 외부 요청이 와서 막았습니다. 서버에서는 OOPS_AUTH_ENABLED=true 로 켜세요. remote={}",
                        req.getRemoteAddr());
                res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                res.setCharacterEncoding(StandardCharsets.UTF_8.name());
                res.getWriter().write("{\"success\":false,\"data\":null,\"error\":{\"code\":\"UNAUTHORIZED\","
                        + "\"message\":\"로그인이 꺼진 서버는 이 컴퓨터에서만 쓸 수 있습니다.\",\"details\":null}}");
                return;
            }
            if (SecurityContextHolder.getContext().getAuthentication() == null
                    || !(SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof AuthenticatedUser)) {
                var principal = users.localDeveloper();
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
                SecurityContextHolder.setContext(context);
            }
            chain.doFilter(req, res);
        }
    }

    /**
     * 이 컴퓨터에서 직접 온 요청인가.
     * nginx 같은 프록시를 거치면 접속 주소는 127.0.0.1 이어도 Host 가 도메인이거나 X-Forwarded-For 가 붙는다.
     * (forward-headers-strategy=framework 이면 접속 주소 자체가 실제 사용자 IP 로 바뀐다)
     */
    static boolean fromThisMachine(HttpServletRequest req) {
        if (!isLoopback(req.getRemoteAddr())) return false;
        String host = req.getServerName();
        if (host == null || !(host.equalsIgnoreCase("localhost") || isLoopback(host))) return false;
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            for (String ip : forwarded.split(",")) if (!isLoopback(ip.strip())) return false;
        }
        return true;
    }

    private static boolean isLoopback(String address) {
        if (address == null) return false;
        String a = address.strip();
        if (a.startsWith("[") && a.endsWith("]")) a = a.substring(1, a.length() - 1);
        return a.equals("127.0.0.1") || a.startsWith("127.") || a.equals("::1") || a.equals("0:0:0:0:0:0:0:1");
    }

    /** 구글 access/refresh 토큰을 세션에 남기지 않는다. 로그인 확인 뒤에는 쓸 일이 없다. */
    static final class NoStoreAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {
        @Override
        public <T extends org.springframework.security.oauth2.client.OAuth2AuthorizedClient> T loadAuthorizedClient(
                String registrationId, Authentication principal, HttpServletRequest request) {
            return null;
        }

        @Override
        public void saveAuthorizedClient(org.springframework.security.oauth2.client.OAuth2AuthorizedClient client,
                                         Authentication principal, HttpServletRequest request, HttpServletResponse response) {
        }

        @Override
        public void removeAuthorizedClient(String registrationId, Authentication principal,
                                           HttpServletRequest request, HttpServletResponse response) {
        }
    }
}
