package com.example.oops.security;

import com.example.oops.config.OopsProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    private ClientIpResolver resolver(List<String> trustedProxies) {
        return new ClientIpResolver(new OopsProperties(
                new OopsProperties.Storage("./uploads", 0, 0),
                new OopsProperties.Analysis(List.of()),
                new OopsProperties.RateLimit(10, 5, trustedProxies)));
    }

    private HttpServletRequest request(String remoteAddr, String xff, String realIp) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (xff != null) request.addHeader("X-Forwarded-For", xff);
        if (realIp != null) request.addHeader("X-Real-IP", realIp);
        return request;
    }

    @Test
    void ignoresForwardedHeadersWhenNoTrustedProxyConfigured() {
        // 기본값: 신뢰 프록시 없음 → 헤더를 위조해도 TCP 피어 주소만 쓴다.
        ClientIpResolver resolver = resolver(List.of());

        String key = resolver.resolve(request("198.51.100.5", "1.2.3.4", "9.9.9.9"));

        assertThat(key).isEqualTo("198.51.100.5");
    }

    @Test
    void spoofedHeaderCannotBypassLimitWithoutTrustedProxy() {
        ClientIpResolver resolver = resolver(List.of());

        // 공격자가 매 요청 다른 XFF 를 넣어도 키는 동일한 피어 주소로 유지된다.
        String first = resolver.resolve(request("198.51.100.5", "10.0.0.1", null));
        String second = resolver.resolve(request("198.51.100.5", "10.0.0.2", null));

        assertThat(first).isEqualTo("198.51.100.5");
        assertThat(second).isEqualTo("198.51.100.5");
    }

    @Test
    void usesForwardedForWhenPeerIsTrustedProxy() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

        String key = resolver.resolve(request("10.0.0.9", "203.0.113.7, 10.0.0.9", null));

        assertThat(key).isEqualTo("203.0.113.7");
    }

    @Test
    void prefersRealIpWhenPeerIsTrustedProxy() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.9"));

        String key = resolver.resolve(request("10.0.0.9", "203.0.113.7", "203.0.113.8"));

        assertThat(key).isEqualTo("203.0.113.8");
    }

    @Test
    void untrustedPeerIgnoresForwardedHeadersEvenWithTrustedListConfigured() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

        // 피어가 신뢰 대역 밖이면 헤더를 보지 않는다.
        String key = resolver.resolve(request("198.51.100.5", "203.0.113.7", "203.0.113.8"));

        assertThat(key).isEqualTo("198.51.100.5");
    }

    @Test
    void malformedForwardedValueFallsBackToPeer() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

        String key = resolver.resolve(request("10.0.0.9", "not-an-ip", null));

        assertThat(key).isEqualTo("10.0.0.9");
    }

    @Test
    void ipv6CidrMatchesTrustedProxy() {
        ClientIpResolver resolver = resolver(List.of("2001:db8::/32"));

        String key = resolver.resolve(request("2001:db8::1", "203.0.113.7", null));

        assertThat(key).isEqualTo("203.0.113.7");
    }
}
