package com.example.oops.security;

import com.example.oops.common.BusinessException;
import com.example.oops.common.ErrorCode;
import com.example.oops.config.OopsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/** 단일 Spring 인스턴스용 IP 기준 고정 윈도우 요청 제한기. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestRateLimiter {

    private final OopsProperties properties;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger sinceSweep =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 이 횟수의 요청마다 한 번만 만료된 윈도우를 정리한다. 매 요청 전체 순회를 피한다. */
    private static final int SWEEP_INTERVAL = 256;
    /** 윈도우 수 상한. 키 폭증(위조·분산 공격)에도 메모리가 무한히 늘지 않게 즉시 정리를 당긴다. */
    private static final int MAX_WINDOWS = 100_000;

    public void check(String operation, String clientKey, int limit) {
        if (limit <= 0) return;

        long window = System.currentTimeMillis() / 60_000L;
        String key = operation + ":" + clientKey;
        Window current = windows.compute(key, (ignored, previous) -> {
            if (previous == null || previous.window() != window) {
                return new Window(window, 1);
            }
            return new Window(window, previous.count() + 1);
        });

        // 전체 맵 순회는 비싸므로 평소에는 일정 요청마다 한 번만 한다.
        // 다만 키가 비정상적으로 많이 쌓이면(공격) 즉시 당겨서 정리한다.
        if (sinceSweep.incrementAndGet() >= SWEEP_INTERVAL || windows.size() > MAX_WINDOWS) {
            sinceSweep.set(0);
            windows.entrySet().removeIf(entry -> entry.getValue().window() < window - 1);
        }

        if (current.count() > limit) {
            log.warn("[rate-limit] operation={} client={} count={} limit={}",
                    operation, clientKey, current.count(), limit);
            throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED,
                    "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    public void checkUpload(String clientKey) {
        check("upload", clientKey,
                properties.rateLimitOrDefault().uploadsPerMinuteOrDefault());
    }

    public void checkRetry(String clientKey) {
        check("retry", clientKey,
                properties.rateLimitOrDefault().retriesPerMinuteOrDefault());
    }

    private record Window(long window, int count) {}
}
