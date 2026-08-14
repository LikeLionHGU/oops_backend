package com.example.oops.client;

import com.example.oops.config.AnalysisServerProperties;
import com.example.oops.domain.SourceType;
import com.example.oops.domain.Video;
import com.example.oops.storage.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Optional;

/**
 * Python 분석 서버(oops-analysis) 호출 담당.
 *
 * 서버가 죽어 있거나 OCR 모델이 없을 때도 전체 분석이 멈추면 안 되므로,
 * 실패는 Optional.empty() 로 흘려보내고 파이프라인이 알아서 건너뛰게 한다.
 */
@Slf4j
@Component
public class AnalysisServerClient {

    /** {"detail":"..."} 에서 메시지만 뽑는다. */
    private static final java.util.regex.Pattern DETAIL_PATTERN =
            java.util.regex.Pattern.compile("\"detail\"\\s*:\\s*\"([^\"]+)\"");

    /** 분석은 스레드마다 하나씩 돌므로 스레드별로 들고 있으면 충분하다. */
    private static final ThreadLocal<String> lastFailure = new ThreadLocal<>();

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
            lastFailure.remove();
            return Optional.ofNullable(response);
        } catch (RestClientException e) {
            String detail = describe(e);
            lastFailure.set(extractDetail(e));
            log.warn("[analysis-server] STT 실패 videoId={} : {}", video.getId(), detail);
            return Optional.empty();
        }
    }

    /**
     * 마지막 호출이 왜 실패했는지.
     *
     * 실패를 조용히 삼키면 "분석했는데 아무것도 없다" 와
     * "분석을 못 했다" 가 구분되지 않는다.
     * 사용자에게는 전혀 다른 이야기이므로 사유를 전달할 수 있어야 한다.
     */
    public Optional<String> lastFailureDetail() {
        return Optional.ofNullable(lastFailure.get());
    }

    /** 분석 서버가 준 detail 만 뽑아낸다. 사용자에게 그대로 보여줄 문장이다. */
    private String extractDetail(RestClientException e) {
        if (!(e instanceof RestClientResponseException re)) {
            return "분석 서버에 연결하지 못했습니다.";
        }
        String body = re.getResponseBodyAsString();
        var matcher = DETAIL_PATTERN.matcher(body);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "분석 서버 오류 (HTTP " + re.getStatusCode().value() + ")";
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
            lastFailure.set(extractDetail(e));
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
