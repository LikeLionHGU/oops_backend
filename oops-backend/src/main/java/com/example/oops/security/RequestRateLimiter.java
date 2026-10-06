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

        windows.entrySet().removeIf(entry -> entry.getValue().window() < window - 1);

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
