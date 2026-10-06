package com.example.oops.controller;

import com.example.oops.client.AnalysisServerClient;
import com.example.oops.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** AWS/Nginx 모니터링이 Spring, Python, 저장소 상태를 한 번에 확인하는 엔드포인트. */
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final AnalysisServerClient analysisServerClient;
    private final StorageService storageService;

    @GetMapping({"/health", "/api/v1/health"})
    public ResponseEntity<HealthResponse> health() {
        boolean analysisServer = analysisServerClient.isHealthy();
        boolean storage = storageService.isWritable();
        boolean healthy = analysisServer && storage;

        return ResponseEntity.status(healthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(new HealthResponse(
                        healthy ? "UP" : "DEGRADED",
                        analysisServer ? "UP" : "DOWN",
                        storage ? "UP" : "DOWN"));
    }

    public record HealthResponse(String status, String analysisServer, String storage) {}
}
