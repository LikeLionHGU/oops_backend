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

    /**
     * "한 바이트도 못 받았다" 는 오류 응답이 아니라 **프로세스가 죽은 것**이다.
     *
     * 3.8GB 서버에서 PaddleOCR 이 3.3GB 를 쓰다 OOM 으로 죽은 적이 있다.
     * 긴 영상에서 특히 그렇다. 이 문장을 못 알아보면 원인을 엉뚱한 데서 찾는다.
     */
    private static final java.util.regex.Pattern DEAD_PROCESS =
            java.util.regex.Pattern.compile("received no bytes|Connection reset|EOF reached|GOAWAY");

    /** {"detail":"..."} 에서 메시지만 뽑는다. */
    private static final java.util.regex.Pattern DETAIL_PATTERN =
            java.util.regex.Pattern.compile("\"detail\"\\s*:\\s*\"([^\"]+)\"");

    /** 분석은 스레드마다 하나씩 돌므로 스레드별로 들고 있으면 충분하다. */
    private static final ThreadLocal<String> lastFailure = new ThreadLocal<>();

    private final RestClient restClient;
    private final RestClient quickClient;
    private final AnalysisServerProperties properties;
    private final StorageService storageService;

    public AnalysisServerClient(@Qualifier("analysisRestClient") RestClient restClient,
                                @Qualifier("analysisQuickRestClient") RestClient quickClient,
                                AnalysisServerProperties properties,
                                StorageService storageService) {
        this.restClient = restClient;
        this.quickClient = quickClient;
        this.properties = properties;
        this.storageService = storageService;
    }

    /**
     * 분석 서버가 살아 있는가.
     *
     * **바쁜 것과 죽은 것은 다르다.**
     * 이 판정으로 업로드를 막는데, 앞 영상의 OCR 이 도는 중이라고 업로드를
     * 거절하면 안 된다. 그건 서버가 정상으로 일하고 있다는 뜻이다.
     *
     * 그래서 짧은 타임아웃으로 묻고, **응답이 늦은 것은 살아 있는 것으로 본다.**
     * 연결 자체가 안 되는 경우(프로세스가 없거나 포트가 닫힘)만 false 다.
     * 막으려는 것은 "파이썬 서버를 안 켰다" 하나뿐이다.
     */
    public boolean isHealthy() {
        long started = System.currentTimeMillis();
        try {
            quickClient.get().uri("/health").retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            long elapsed = System.currentTimeMillis() - started;
            if (isConnectFailure(e)) {
                log.warn("[analysis-server] 연결되지 않습니다 ({}ms) — {}", elapsed, rootMessage(e));
                return false;
            }
            // 붙기는 했는데 답이 늦다. OCR 로 바쁜 것이다. 살아 있다고 본다.
            log.warn("[analysis-server] 응답이 늦습니다 ({}ms). 바쁜 것으로 보고 계속합니다 — {}",
                    elapsed, rootMessage(e));
            return true;
        }
    }

    /** 연결이 아예 안 된 것인지(= 서버가 없음), 붙었는데 느린 것인지 가른다. */
    private boolean isConnectFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.ConnectException
                    || t instanceof java.net.UnknownHostException
                    || t instanceof java.nio.channels.UnresolvedAddressException) {
                return true;
            }
            if (t instanceof java.net.http.HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    /**
     * 길이만 재고 온다. 업로드 직후 90분 초과를 걸러내는 데 쓴다.
     *
     * 분석을 다 돌린 뒤에 "너무 깁니다" 라고 하면 이미 STT 비용이 나간 뒤다.
     * 실패하면 empty 를 주고, 호출한 쪽은 그냥 통과시킨다.
     * 길이를 못 쟀다는 이유로 업로드 자체를 막으면 더 나쁘다.
     */
    public Optional<ProbeResponse> probe(Video video) {
        try {
            // 업로드 파일은 로컬에 있으므로 ffprobe 한 번이면 끝난다.
            // 10분 클라이언트로 보내면 서버가 바쁠 때 업로드가 그만큼 매달린다.
            ProbeResponse response = quickClient.post()
                    .uri("/probe")
                    .body(toRequest(video, null, null))
                    .retrieve()
                    .body(ProbeResponse.class);
            return Optional.ofNullable(response);
        } catch (RestClientException e) {
            log.warn("[analysis-server] 길이 확인 실패 videoId={} : {}", video.getId(), describe(e));
            return Optional.empty();
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
        String message = e.getMessage() == null ? e.toString() : e.getMessage();

        // 오류 응답이 아니라 프로세스가 사라진 경우. 원인을 같이 알려준다.
        if (DEAD_PROCESS.matcher(message).find()) {
            return message + "\n"
                    + "  → 오류 응답이 아니라 분석 서버 프로세스가 죽은 것입니다."
                    + " 메모리 부족(OOM)이 가장 흔한 원인이고 긴 영상에서 특히 그렇습니다.\n"
                    + "  → 확인:  dmesg -T | grep -i 'killed process'   ·   free -h\n"
                    + "  → 대응:  스왑 2GB + MAX_OCR_FRAMES 를 80 으로."
                    + " 그다음 분석 서버를 다시 띄우세요. (README '서버에서 겪은 문제와 해결')";
        }
        return message;
    }

    private MediaRequest toRequest(Video video, Double intervalSec, String frameDir) {
        if (video.getSourceType() == SourceType.YOUTUBE) {
            return new MediaRequest(video.getSourceUrl(), null, intervalSec, frameDir);
        }
        String path = storageService.resolve(video.getStorageKey()).toString();
        return new MediaRequest(null, path, intervalSec, frameDir);
    }

    public record MediaRequest(String videoUrl, String filePath, Double intervalSec, String frameDir) {}

    public record ProbeResponse(Integer durationSec, String title,
                                Integer maxDurationSec, Boolean withinLimit) {}

    public record TranscribeResponse(String language, String title, Integer durationSec,
                                     List<Segment> segments) {
        public record Segment(long startMs, long endMs, String text) {}
    }

    public record OcrResponse(List<Item> items) {
        public record Item(long startMs, long endMs, String text, Double confidence, String framePath) {}
    }
}
