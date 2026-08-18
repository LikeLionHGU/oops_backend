package com.example.oops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 허용할 Origin 목록. 명세 §11.
 *
 * `@Value("${oops.cors.allowed-origins}")` 로는 못 읽는다.
 * YAML 의 `- item` 리스트는 `allowed-origins[0]`, `[1]` 로 저장돼서
 * `allowed-origins` 라는 키 자체가 없기 때문이다.
 * 그래서 다른 설정들과 같이 @ConfigurationProperties 로 받는다.
 */
@ConfigurationProperties(prefix = "oops.cors")
public record CorsProperties(List<String> allowedOrigins) {

    /**
     * 설정이 비어 있어도 로컬 개발은 되게 한다.
     * 서버가 안 뜨는 것보다 낫고, 배포에서는 반드시 채우게 된다.
     */
    public List<String> originsOrDefault() {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            return List.of("http://localhost:5173", "http://localhost:3000",
                    "http://localhost:8080");
        }
        return allowedOrigins;
    }
}
