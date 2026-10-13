package com.example.oops.security;

import com.example.oops.config.OopsProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 레이트리밋 등 IP 기준 식별의 단일 진입점.
 *
 * 핵심 규칙: X-Forwarded-For / X-Real-IP 는 클라이언트가 위조할 수 있으므로
 * 기본적으로 신뢰하지 않는다. 직접 연결한 피어(getRemoteAddr)가 설정된
 * 신뢰 프록시 목록(oops.rate-limit.trusted-proxies)에 들 때만 forwarded 헤더를
 * 읽어 실제 클라이언트 IP 를 판정한다.
 *
 * 신뢰 목록이 비어 있으면(프록시 뒤가 아닌 배포, 로컬) forwarded 헤더를 전혀
 * 보지 않고 getRemoteAddr 만 쓴다. 이렇게 하면 공격자가 매 요청마다 헤더를
 * 바꿔 레이트리밋 키를 분산시키는 우회를 막는다(fail-closed).
 */
@Slf4j
@Component
public class ClientIpResolver {

    private final List<Cidr> trustedProxies;

    public ClientIpResolver(OopsProperties properties) {
        this.trustedProxies = properties.rateLimitOrDefault().trustedProxiesOrDefault().stream()
                .map(Cidr::parse)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (!trustedProxies.isEmpty()) {
            log.info("[client-ip] 신뢰 프록시 {}개 설정됨 — 해당 피어의 forwarded 헤더만 신뢰합니다.",
                    trustedProxies.size());
        }
    }

    /**
     * 레이트리밋 등에 쓸 클라이언트 식별 키.
     * 신뢰 프록시 뒤에서 온 요청만 forwarded 헤더의 첫 IP 를 쓰고,
     * 그 외에는 TCP 피어 주소(getRemoteAddr)를 그대로 쓴다.
     */
    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (!trustedProxy(peer)) {
            return peer == null ? "unknown" : peer;
        }

        String realIp = header(request.getHeader("X-Real-IP"));
        if (realIp != null) return realIp;

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // 체인의 맨 앞(원 클라이언트)이 우리가 신뢰하는 프록시가 붙인 값이다.
            String first = header(forwarded.split(",", 2)[0]);
            if (first != null) return first;
        }
        return peer == null ? "unknown" : peer;
    }

    private boolean trustedProxy(String peer) {
        if (peer == null || trustedProxies.isEmpty()) return false;
        InetAddress address = parse(peer);
        if (address == null) return false;
        return trustedProxies.stream().anyMatch(cidr -> cidr.contains(address));
    }

    private static String header(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        // 위조된 헤더가 뒷단에서 또 다른 헤더처럼 보이지 않게 실제 IP 형태만 통과시킨다.
        return parse(trimmed) == null ? null : trimmed;
    }

    private static InetAddress parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            // getByName 은 호스트명도 DNS 로 해석하므로, 리터럴 IP 가 아닌 입력은 거른다.
            if (!value.matches("[0-9a-fA-F:.]+")) return null;
            return InetAddress.getByName(value);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** 단일 주소 또는 CIDR(a.b.c.d/n, ipv6/n)을 바이트 접두사 비교로 매칭한다. */
    record Cidr(byte[] network, int prefixBits) {

        static Cidr parse(String spec) {
            if (spec == null || spec.isBlank()) return null;
            String trimmed = spec.trim();
            int slash = trimmed.indexOf('/');
            String host = slash < 0 ? trimmed : trimmed.substring(0, slash);
            InetAddress base = ClientIpResolver.parse(host);
            if (base == null) {
                log.warn("[client-ip] 신뢰 프록시 설정을 해석하지 못해 무시합니다: {}", spec);
                return null;
            }
            byte[] bytes = base.getAddress();
            int maxBits = bytes.length * 8;
            int prefix = maxBits;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(trimmed.substring(slash + 1).trim());
                } catch (NumberFormatException e) {
                    log.warn("[client-ip] CIDR 접두사를 해석하지 못해 무시합니다: {}", spec);
                    return null;
                }
                if (prefix < 0 || prefix > maxBits) {
                    log.warn("[client-ip] CIDR 접두사 범위가 올바르지 않아 무시합니다: {}", spec);
                    return null;
                }
            }
            return new Cidr(bytes, prefix);
        }

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            // IPv4 와 IPv6 는 섞어서 비교하지 않는다.
            if (candidate.length != network.length) return false;
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) return false;
            }
            int remainingBits = prefixBits % 8;
            if (remainingBits == 0) return true;
            int mask = 0xFF << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
