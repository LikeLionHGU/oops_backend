package com.example.videoguard.client;

import com.example.videoguard.config.AnalysisServerProperties;
import com.example.videoguard.domain.SourceType;
import com.example.videoguard.domain.Video;
import com.example.videoguard.storage.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Optional;

/**
 * Python 분석 서버(videoguard-analysis) 호출 담당.
 *
 * 서버가 죽어 있거나 OCR 모델이 없을 때도 전체 분석이 멈추면 안 되므로,
 * 실패는 Optional.empty() 로 흘려보내고 파이프라인이 알아서 건너뛰게 한다.
 */
@Slf4j
@Component
public class AnalysisServerClient {

    private final RestClient restClient;
    private final AnalysisServerProperties properties;
    private final StorageService storageService;

    public AnalysisServerClient(@Qualifier("analysisRestClient") RestClient restClient,
                                AnalysisServerProperties properties,
                                StorageService storageService) {
        this.restClient = restClient;
        this.properties = properties;
        this.storageService = storageService;
    }

    public boolean isHealthy() {
        try {
            restClient.get().uri("/health").retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            return false;
        }
    }

    public Optional<TranscribeResponse> transcribe(Video video) {
        try {
            TranscribeResponse response = restClient.post()
                    .uri("/transcribe")
                    .body(toRequest(video, null, null))
                    .retrieve()
                    .body(TranscribeResponse.class);
            return Optional.ofNullable(response);
        } catch (RestClientException e) {
            log.warn("[analysis-server] STT 실패 videoId={} : {}", video.getId(), describe(e));
            return Optional.empty();
        }
    }

    public Optional<OcrResponse> ocr(Video video) {
        try {
            String frameDir = storageService.frameDir(video.getId()).toString();
            OcrResponse response = restClient.post()
                    .uri("/ocr")
                    .body(toRequest(video, properties.ocrIntervalOrDefault(), frameDir))
                    .retrieve()
                    .body(OcrResponse.class);
            return Optional.ofNullable(response);
        } catch (RestClientException e) {
            // 503 = OCR 미설치. 이 경우는 정상 시나리오다.
            log.warn("[analysis-server] OCR 건너뜀 videoId={} : {}", video.getId(), describe(e));
            return Optional.empty();
        }
    }

    /** 분석 서버가 돌려준 에러 본문까지 로그에 남긴다. 원인 파악이 훨씬 빨라진다. */
    private String describe(RestClientException e) {
        if (e instanceof RestClientResponseException re) {
            return "HTTP %d %s".formatted(re.getStatusCode().value(), re.getResponseBodyAsString());
        }
        return e.getMessage();
    }

    private MediaRequest toRequest(Video video, Double intervalSec, String frameDir) {
        if (video.getSourceType() == SourceType.YOUTUBE) {
            return new MediaRequest(video.getSourceUrl(), null, intervalSec, frameDir);
        }
        String path = storageService.resolve(video.getStorageKey()).toString();
        return new MediaRequest(null, path, intervalSec, frameDir);
    }

    public record MediaRequest(String videoUrl, String filePath, Double intervalSec, String frameDir) {}

    public record TranscribeResponse(String language, String title, Integer durationSec,
                                     List<Segment> segments) {
        public record Segment(long startMs, long endMs, String text) {}
    }

    public record OcrResponse(List<Item> items) {
        public record Item(long startMs, long endMs, String text, Double confidence, String framePath) {}
    }
}
