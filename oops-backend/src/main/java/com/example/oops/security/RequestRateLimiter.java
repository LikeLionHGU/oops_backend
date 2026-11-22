package com.example.oops.security;

import com.example.oops.common.BusinessException;
import com.example.oops.common.ErrorCode;
import com.example.oops.config.OopsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** 단일 Spring 인스턴스용 IP 기준 고정 윈도우 요청 제한기. */
@Slf4j
@Component
public class RequestRateLimiter {

    private final OopsProperties properties;
    private final Clock clock;
    private final int maxWindows;
    /** All access is serialized by increment; active counters are never evicted to admit new keys. */
    private final Map<String, Integer> windows = new HashMap<>();
    private long currentWindow = Long.MIN_VALUE;

    private static final int MAX_WINDOWS = 100_000;

    @Autowired
    public RequestRateLimiter(OopsProperties properties) {
        this(properties, Clock.systemUTC(), MAX_WINDOWS);
    }

    RequestRateLimiter(OopsProperties properties, Clock clock, int maxWindows) {
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
        if (maxWindows < 1) throw new IllegalArgumentException("maxWindows must be positive");
        this.maxWindows = maxWindows;
    }

    public void check(String operation, String clientKey, int limit) {
        if (limit <= 0) return;

        long count = increment(operation + ":" + clientKey);
        if (count == 0) {
            throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED,
                    "현재 요청 처리 한도에 도달했습니다. 잠시 후 다시 시도해 주세요.");
        }

        if (count > limit) {
            log.warn("[rate-limit] operation={} client={} count={} limit={}",
                    operation, clientKey, count, limit);
            throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED,
                    "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private synchronized long increment(String key) {
        long window = Math.floorDiv(clock.millis(), 60_000L);
        // Fixed-window quotas only need the current minute. Clear at most once per advance,
        // not on every rejected key. A backwards clock must not reset an active quota.
        if (window > currentWindow) {
            windows.clear();
            currentWindow = window;
        }
        Integer previous = windows.get(key);
        if (previous == null && windows.size() >= maxWindows) return 0;
        long count = previous == null ? 1L : previous.longValue() + 1;
        windows.put(key, (int) Math.min(count, Integer.MAX_VALUE));
        return count;
    }

    public void checkUpload(String clientKey) {
        check("upload", clientKey,
                properties.rateLimitOrDefault().uploadsPerMinuteOrDefault());
    }

    public void checkRetry(String clientKey) {
        check("retry", clientKey,
                properties.rateLimitOrDefault().retriesPerMinuteOrDefault());
    }

}
