package com.example.oops.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class LocalDiagnosticsAccessTest {
    @Test void requiresLocalProfileExplicitBindingAndLocalPeer() throws Exception {
        var env = new MockEnvironment(); var gate = new LocalDiagnosticsAccess(env);
        var request = new MockHttpServletRequest(); request.setRemoteAddr("127.0.0.1");
        assertThat(gate.preHandle(request, new MockHttpServletResponse(), null)).isFalse();
        env.setActiveProfiles("local");
        assertThat(gate.preHandle(request, new MockHttpServletResponse(), null)).isFalse();
        env.setProperty("server.address", "127.0.0.1");
        assertThat(gate.preHandle(request, new MockHttpServletResponse(), null)).isTrue();
        request.setRemoteAddr("192.168.1.1");
        assertThat(gate.preHandle(request, new MockHttpServletResponse(), null)).isFalse();
    }
    @Test void rejectsProxyHeadersEvenWithLocalPeer() throws Exception {
        var env = new MockEnvironment().withProperty("server.address", "127.0.0.1"); env.setActiveProfiles("local");
        for (String header : new String[]{"Forwarded", "X-Forwarded-For", "X-Forwarded-Host"}) {
            var request = new MockHttpServletRequest(); request.setRemoteAddr("127.0.0.1"); request.addHeader(header, "x");
            var response = new MockHttpServletResponse();
            assertThat(new LocalDiagnosticsAccess(env).preHandle(request, response, null)).isFalse();
            assertThat(response.getStatus()).isEqualTo(403);
        }
    }
}
