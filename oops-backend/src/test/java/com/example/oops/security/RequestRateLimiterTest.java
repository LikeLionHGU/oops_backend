package com.example.oops.security;

import com.example.oops.common.BusinessException;
import com.example.oops.common.ErrorCode;
import com.example.oops.config.OopsProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.time.*;
import java.util.concurrent.*;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

class RequestRateLimiterTest {

    @Test
    void uploadsAreLimitedPerClientWithinTheSameMinute() {
        OopsProperties properties = new OopsProperties(
                new OopsProperties.Storage("./uploads", 0, 0),
                new OopsProperties.Analysis(List.of()),
                new OopsProperties.RateLimit(2, 5, null));
        RequestRateLimiter limiter = new RequestRateLimiter(properties);

        limiter.checkUpload("203.0.113.10");
        limiter.checkUpload("203.0.113.10");

        assertThatThrownBy(() -> limiter.checkUpload("203.0.113.10"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED);
    }

    @Test
    void differentClientsHaveIndependentLimits() {
        OopsProperties properties = new OopsProperties(
                new OopsProperties.Storage("./uploads", 0, 0),
                new OopsProperties.Analysis(List.of()),
                new OopsProperties.RateLimit(1, 5, null));
        RequestRateLimiter limiter = new RequestRateLimiter(properties);

        limiter.checkUpload("203.0.113.10");
        limiter.checkUpload("203.0.113.11");
    }

    @Test void capacityRejectsNewKeysWithoutResettingExistingQuotas() {
        var limiter = limiter(new MutableClock(), 2);
        limiter.check("upload", "first", 2);
        limiter.check("upload", "second", 2);
        assertThatThrownBy(() -> limiter.check("upload", "third", 2)).isInstanceOf(BusinessException.class);
        limiter.check("upload", "first", 2);
        assertThatThrownBy(() -> limiter.check("upload", "first", 2)).isInstanceOf(BusinessException.class);
        assertThat(size(limiter)).isEqualTo(2);
    }

    @Test void nextMinuteReclaimsCapacityAndBackwardsClockDoesNotResetQuotas() {
        var clock = new MutableClock();
        var limiter = limiter(clock, 1);
        limiter.check("upload", "first", 1);
        clock.now = clock.now.minusSeconds(60);
        assertThatThrownBy(() -> limiter.check("upload", "first", 1)).isInstanceOf(BusinessException.class);
        clock.now = clock.now.plusSeconds(120);
        limiter.check("upload", "second", 1);
        assertThat(size(limiter)).isOne();
    }

    @Test void disabledLimitsDoNotRetainKeys() {
        var limiter = limiter(new MutableClock(), 1);
        for (int i = 0; i < 1_000; i++) limiter.check("upload", "client-" + i, 0);
        assertThat(size(limiter)).isZero();
    }

    @Test void parallelNewKeysCannotExceedCapacity() throws Exception {
        var limiter = limiter(new MutableClock(), 32);
        assertThat(acceptedConcurrently(limiter, false, 200, 10)).isEqualTo(32);
        assertThat(size(limiter)).isEqualTo(32);
    }

    @Test void parallelRequestsCannotBypassAnExistingClientsQuota() throws Exception {
        var limiter = limiter(new MutableClock(), 32);
        assertThat(acceptedConcurrently(limiter, true, 40, 5)).isEqualTo(5);
        assertThat(size(limiter)).isOne();
    }

    @Test void repeatedHighCardinalityWindowsStayBounded() {
        var clock = new MutableClock();
        var limiter = limiter(clock, 64);
        for (int minute = 0; minute < 120; minute++) {
            int accepted = 0;
            for (int i = 0; i < 100; i++) {
                try { limiter.check("upload", minute + ":" + i, 1); accepted++; }
                catch (BusinessException e) { assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED); }
            }
            assertThat(accepted).isEqualTo(64);
            assertThat(size(limiter)).isEqualTo(64);
            clock.now = clock.now.plusSeconds(60);
        }
    }

    @Test void uploadAndRetryQuotasRemainIndependent() {
        var limiter = limiter(new MutableClock(), 2);
        limiter.check("upload", "same-client", 1);
        limiter.check("retry", "same-client", 1);
        assertThatThrownBy(() -> limiter.check("upload", "same-client", 1)).isInstanceOf(BusinessException.class);
    }

    private int acceptedConcurrently(RequestRateLimiter limiter, boolean sameKey, int requests, int limit) throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<Boolean>>();
        try {
            for (int i = 0; i < requests; i++) {
                String key = sameKey ? "same" : "client-" + i;
                results.add(pool.submit(() -> {
                    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                    try { limiter.check("upload", key, limit); return true; }
                    catch (BusinessException e) {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED);
                        return false;
                    }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) accepted++;
            return accepted;
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private RequestRateLimiter limiter(Clock clock, int capacity) {
        return new RequestRateLimiter(new OopsProperties(new OopsProperties.Storage("./uploads", 0, 0),
                new OopsProperties.Analysis(List.of()), new OopsProperties.RateLimit(2, 5, null)), clock, capacity);
    }

    private int size(RequestRateLimiter limiter) {
        return ((Map<?, ?>) ReflectionTestUtils.getField(limiter, "windows")).size();
    }

    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-22T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
