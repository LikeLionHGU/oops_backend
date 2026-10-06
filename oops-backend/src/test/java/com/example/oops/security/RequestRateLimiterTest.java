package com.example.oops.security;

import com.example.oops.common.BusinessException;
import com.example.oops.common.ErrorCode;
import com.example.oops.config.OopsProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestRateLimiterTest {

    @Test
    void uploadsAreLimitedPerClientWithinTheSameMinute() {
        OopsProperties properties = new OopsProperties(
                new OopsProperties.Storage("./uploads", 0, 0),
                new OopsProperties.Analysis(List.of()),
                new OopsProperties.RateLimit(2, 5));
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
                new OopsProperties.RateLimit(1, 5));
        RequestRateLimiter limiter = new RequestRateLimiter(properties);

        limiter.checkUpload("203.0.113.10");
        limiter.checkUpload("203.0.113.11");
    }
}
