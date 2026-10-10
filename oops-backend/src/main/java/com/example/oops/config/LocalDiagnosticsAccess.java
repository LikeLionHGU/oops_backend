package com.example.oops.config;

import jakarta.servlet.http.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import java.util.Set;

/** Fail closed: a loopback proxy alone is not proof of a local user. */
@Component
public class LocalDiagnosticsAccess implements HandlerInterceptor, WebMvcConfigurer {
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
    private final Environment environment;
    public LocalDiagnosticsAccess(Environment environment) { this.environment = environment; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/videos/*/analysis/diagnostics",
                "/api/v1/videos/*/analysis/stt-review-plan", "/api/v1/videos/*/analysis/request-traces");
    }
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!environment.matchesProfiles("local") || !LOOPBACK.contains(environment.getProperty("server.address", ""))
                || !LOOPBACK.contains(request.getRemoteAddr()) || request.getHeader("Forwarded") != null
                || request.getHeader("X-Forwarded-For") != null || request.getHeader("X-Forwarded-Host") != null) {
            response.sendError(403); return false;
        }
        return true;
    }
}
