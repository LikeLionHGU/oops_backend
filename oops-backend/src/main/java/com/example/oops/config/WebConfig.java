package com.example.oops.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * CORS. 명세 §11.
 *
 * 프론트가 다른 도메인(Vite dev server, Vercel 등)에서 호출하므로
 * 서버가 허용 헤더를 주지 않으면 브라우저가 모든 요청을 막습니다.
 *
 * 허용 목록은 application.yml 의 oops.cors.allowed-origins 에 둡니다.
 * 배포 도메인이 정해지면 코드가 아니라 설정만 고치면 됩니다.
 *
 * `*` 를 쓰지 않는 이유는, 나중에 쿠키 인증을 붙이면 와일드카드로는
 * credentials 를 허용할 수 없어서 그때 다시 고쳐야 하기 때문입니다.
 */
@Slf4j
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final List<String> allowedOrigins;

    public WebConfig(CorsProperties properties) {
        this.allowedOrigins = properties.originsOrDefault();
        log.info("[cors] 허용 Origin: {}", allowedOrigins);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Accept", "Range")
                // 영상 재생에 필요하다. 노출하지 않으면 브라우저가 Range 응답을 못 읽는다.
                .exposedHeaders("Content-Range", "Accept-Ranges", "Content-Length")
                .allowCredentials(true)
                // Preflight 결과를 하루 캐시한다. PUT + JSON 은 매번 OPTIONS 가 먼저 날아온다.
                .maxAge(86400);
    }
}
