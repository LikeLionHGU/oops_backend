package com.example.videoguard.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Swagger UI: http://localhost:8080/swagger-ui.html
 * OpenAPI 원본: http://localhost:8080/api-docs
 *
 * API 명세 16번 "Swagger/OpenAPI = Source of Truth" 에 해당한다.
 * 컨트롤러와 DTO 에서 자동 생성되므로 코드를 고치면 문서도 같이 바뀐다.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI videoguardOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Creator Risk Manager API")
                        .version("v1")
                        .description("""
                                영상을 올리면 논란이 될 만한 구간을 찾아 타임라인으로 돌려주는 API.

                                ## 기본 흐름
                                1. `POST /api/v1/videos` 로 영상 업로드 (업로드 즉시 분석이 시작된다)
                                2. WebSocket `/ws` 연결 후 `/topic/videos/{videoId}/progress` 구독
                                   (또는 `GET /api/v1/videos/{videoId}/status` 폴링)
                                3. 완료되면 `GET /api/v1/videos/{videoId}/report` 로 결과 조회
                                4. `GET /api/v1/videos/{videoId}/stream` 으로 재생, 카드 클릭 시 `startMs` 로 이동

                                ## 응답 형식
                                성공: `{ "success": true, "message": "...", "data": {...} }`
                                실패: `{ "success": false, "message": "...", "error": { "code": "...", "traceId": "..." } }`

                                `error.code` 로 분기하면 된다. `traceId` 는 서버 로그 추적용이다.

                                ## 시간 단위
                                모든 시각은 밀리초(ms) 정수다. 프론트에서는 `startMs / 1000` 으로 초 단위로 바꿔 쓴다.
                                """))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("로컬 개발")
                ));
    }
}
