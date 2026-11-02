package com.example.oops.auth;

import com.example.oops.domain.SourceType;
import com.example.oops.domain.Video;
import com.example.oops.repository.VideoRepository;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 로그인을 켠 서버에서의 인증·영상 주인 검사. 구글은 실제로 부르지 않고 로그인 결과(세션)를 직접 만든다. */
@SpringBootTest(properties = {
        "oops.auth.enabled=true",
        "oops.auth.google.client-id=test-client",
        "oops.auth.google.client-secret=test-secret",
        "oops.auth.success-redirect=https://front.example/",
        "oops.auth.admin-emails=admin@example.com"})
@ActiveProfiles("test")
class AuthSecurityTest {

    @Autowired WebApplicationContext context;
    @Autowired AppUserService users;
    @Autowired VideoRepository videos;
    @MockitoBean com.example.oops.client.AnalysisServerClient analysisServer;

    MockMvc mvc;
    static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        Filter security = context.getBean("springSecurityFilterChain", Filter.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
    }

    MockHttpSession loginAs(AppUser user) {
        var principal = new AuthenticatedUser(user.getId(), user.getDisplayName(), user.getEmail(), false);
        var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(auth));
        return session;
    }

    Video videoOf(Long owner, String name) {
        var v = Video.builder().sourceType(SourceType.UPLOAD).filename(name).title(name).build();
        v.assignOwner(owner);
        return videos.save(v);
    }

    String csrf(MockHttpSession session) throws Exception {
        String body = mvc.perform(get("/api/v1/auth/csrf").session(session)).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("data").get("token").asString();
    }

    @Test void anonymousRequestsGet401WithLoginUrlButMeAnswersNotLoggedIn() throws Exception {
        var res = mvc.perform(get("/api/v1/videos/history")).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("UNAUTHORIZED").contains("/oauth2/authorization/google");

        var me = mvc.perform(get("/api/v1/auth/me")).andReturn().getResponse();
        assertThat(me.getStatus()).isEqualTo(200);
        assertThat(me.getContentAsString()).contains("\"loggedIn\":false").contains("\"authEnabled\":true");
    }

    @Test void loginStartRedirectsToGoogle() throws Exception {
        var res = mvc.perform(get("/oauth2/authorization/google")).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(302);
        assertThat(res.getRedirectedUrl()).startsWith("https://accounts.google.com/o/oauth2/v2/auth").contains("client_id=test-client");
    }

    @Test void usersSeeOnlyTheirOwnVideosAndLegacyVideosAreHidden() throws Exception {
        var alice = users.loginWithGoogle("https://accounts.google.com", "alice-sub", "앨리스", "a@example.com", true);
        var bob = users.loginWithGoogle("https://accounts.google.com", "bob-sub", "밥", "b@example.com", true);
        var mine = videoOf(alice.getId(), "alice.mp4");
        var theirs = videoOf(bob.getId(), "bob.mp4");
        var legacy = videoOf(null, "legacy.mp4");
        var session = loginAs(alice);

        var history = mvc.perform(get("/api/v1/videos/history").session(session)).andReturn().getResponse();
        assertThat(history.getStatus()).isEqualTo(200);
        assertThat(history.getContentAsString()).contains("alice.mp4").doesNotContain("bob.mp4").doesNotContain("legacy.mp4");

        for (var other : List.of(theirs, legacy)) {
            for (String path : List.of("/status", "/report", "/transcript", "/stream", "/review-actions")) {
                var res = mvc.perform(get("/api/v1/videos/" + other.getId() + path).session(session)).andReturn().getResponse();
                assertThat(res.getStatus()).as(path).isEqualTo(404);
                assertThat(res.getContentAsString()).as(path).contains("VIDEO_NOT_FOUND");
            }
        }
        var own = mvc.perform(get("/api/v1/videos/" + mine.getId() + "/status").session(session)).andReturn().getResponse();
        assertThat(own.getStatus()).isNotIn(401, 403, 404);   // 분석 전이라 상태 오류일 수는 있다
    }

    @Test void stateChangingRequestsNeedCsrfToken() throws Exception {
        var carol = users.loginWithGoogle("https://accounts.google.com", "carol-sub", "캐럴", "c@example.com", true);
        var mine = videoOf(carol.getId(), "carol.mp4");
        var session = loginAs(carol);
        var without = mvc.perform(post("/api/v1/videos/" + mine.getId() + "/analysis/cancel").session(session)).andReturn().getResponse();
        assertThat(without.getStatus()).isEqualTo(403);
        String token = csrf(session);
        var with = mvc.perform(post("/api/v1/videos/" + mine.getId() + "/analysis/cancel").session(session)
                .header("X-CSRF-TOKEN", token)).andReturn().getResponse();
        assertThat(with.getStatus()).isNotIn(401, 403, 404);
    }

    @Test void metricsAreForAdminsOnly() throws Exception {
        var dave = users.loginWithGoogle("https://accounts.google.com", "dave-sub", "데이브", "d@example.com", true);
        var admin = users.loginWithGoogle("https://accounts.google.com", "admin-sub", "운영자", "admin@example.com", true);
        assertThat(mvc.perform(get("/api/v1/metrics").session(loginAs(dave))).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get("/api/v1/metrics").session(loginAs(admin))).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test void sameGoogleSubjectIsTheSameUserButSameEmailIsNotMerged() {
        var first = users.loginWithGoogle("https://accounts.google.com", "same-sub", "이름1", "same@example.com", true);
        var again = users.loginWithGoogle("https://accounts.google.com", "same-sub", "이름2", "same@example.com", true);
        var other = users.loginWithGoogle("https://accounts.google.com", "other-sub", "이름3", "same@example.com", true);
        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(again.getDisplayName()).isEqualTo("이름2");
        assertThat(other.getId()).isNotEqualTo(first.getId());
    }
}
