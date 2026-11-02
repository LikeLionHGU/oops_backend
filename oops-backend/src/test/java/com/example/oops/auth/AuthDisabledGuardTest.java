package com.example.oops.auth;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 로그인을 끈 서버는 이 컴퓨터에서 직접 온 요청만 받는다 (서버에서 실수로 꺼져도 외부에 열리지 않게). */
@SpringBootTest(properties = "oops.auth.enabled=false")
@ActiveProfiles("test")
class AuthDisabledGuardTest {

    @Autowired WebApplicationContext context;
    @MockitoBean com.example.oops.client.AnalysisServerClient analysisServer;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        Filter security = context.getBean("springSecurityFilterChain", Filter.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
    }

    @Test void localRequestsWorkAsLocalDeveloper() throws Exception {
        var res = mvc.perform(get("/api/v1/auth/me")).andReturn().getResponse();   // 127.0.0.1, localhost
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentAsString()).contains("\"loggedIn\":true").contains("\"authEnabled\":false");
    }

    @Test void externalOrProxiedRequestsAreRejected() throws Exception {
        var external = mvc.perform(get("/api/v1/videos/history").with(r -> { r.setRemoteAddr("203.0.113.5"); return r; }))
                .andReturn().getResponse();
        assertThat(external.getStatus()).isEqualTo(401);
        assertThat(external.getContentAsString()).contains("이 컴퓨터에서만");

        var viaDomain = mvc.perform(get("https://api.oops-im-sorry.online/api/v1/videos/history")).andReturn().getResponse();
        assertThat(viaDomain.getStatus()).isEqualTo(401);

        var forwarded = mvc.perform(get("/api/v1/videos/history").header("X-Forwarded-For", "198.51.100.7"))
                .andReturn().getResponse();
        assertThat(forwarded.getStatus()).isEqualTo(401);
    }
}
