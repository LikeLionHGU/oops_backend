package com.example.oops.client;

import com.example.oops.config.OpenAiProperties;
import tools.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAI Chat Completions 호출.
 * JSON 강제 모드로 받아서 곧바로 DTO 로 역직렬화한다.
 * 키가 없거나 호출이 실패하면 empty 를 돌려주고, 호출부는 룰 기반 결과만으로 진행한다.
 */
@Slf4j
@Component
public class OpenAiClient {

    /** Provider JSON-mode requirement, independent of analyzer-specific prompt wording. */
    public static final String JSON_OUTPUT_INSTRUCTION = "반드시 유효한 JSON 객체만 반환한다. JSON을 코드 블록으로 감싸거나 객체 밖에 설명을 덧붙이지 마라.";

    /** 429 를 만났을 때 재시도 횟수 */
    private static final int MAX_ATTEMPTS = 4;

    /**
     * 재시도 대기 상한.
     *
     * OpenAI 가 Retry-After 로 수십 분을 요구하는 경우가 있다.
     * 이건 분당 한도가 아니라 일일 한도(RPD)를 다 썼다는 뜻이다.
     * 그만큼 기다리는 건 멈춘 것과 같으므로, 상한을 넘으면 즉시 포기하고
     * 부분 결과라도 돌려준다.
     */
    private static final long MAX_WAIT_MS = 30_000;

    /** 한도 대비 여유분. 경계에서 걸리지 않도록 90% 만 쓴다. (main 757d842·bb4c3cc·cb8ad6d 이식) */
    private static final double HEADROOM = 0.9;

    /** "1m30s", "6ms", "2.5s" 같은 헤더 값을 밀리초로 읽는다. */
    private static final Pattern DURATION_PART = Pattern.compile("([0-9.]+)(ms|s|m|h)");

    /**
     * 요청 사이 최소 간격. 시작은 설정값(requests-per-minute)으로, 응답 헤더로 계정 한도를 알면
     * 분당 요청·분당 토큰 중 느린 쪽에 맞춰 실행 중에 조정된다. 429 를 맞으면 두 배로 늘린다.
     * 동시 호출 수 상한(max-concurrent)과 별개로 서버 전체 속도를 정한다.
     */
    private static final AtomicLong minIntervalMs = new AtomicLong(6_000);

    /** 헤더에서 읽은 계정 한도(분당 요청·분당 토큰). 모르면 0 */
    private static final AtomicLong knownRpm = new AtomicLong(0);
    private static final AtomicLong knownTpm = new AtomicLong(0);

    /** 호출 하나가 쓰는 평균 토큰(입력+출력). 분당 토큰 한도를 간격으로 바꾸는 데 쓴다. */
    private static final AtomicLong avgTokensPerCall = new AtomicLong(3_000);
    private static final AtomicLong measuredCalls = new AtomicLong(0);
    private static final AtomicLong measuredTokens = new AtomicLong(0);

    /** "try again in 6s" 같은 안내에서 대기 시간을 뽑아낸다. */
    private static final Pattern RETRY_HINT =
            Pattern.compile("try again in ([0-9.]+)(ms|s)", Pattern.CASE_INSENSITIVE);

    /** 여러 분석 스레드가 공유한다. 다음 요청을 보낼 수 있는 가장 이른 시각(순번 예약). */
    private static final AtomicLong nextSlotAt = new AtomicLong(0);

    /** 계정의 실제 한도를 한 번만 찍는다. 매 호출마다 찍으면 로그가 지저분해진다. */
    private static final java.util.concurrent.atomic.AtomicBoolean limitLogged =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final RestClient restClient;
    private final OpenAiProperties properties;
    private final boolean structuredDiscoveryOutput;

    /**
     * LLM 응답 문자열을 파싱하는 용도로만 쓴다.
     * Spring 이 관리하는 JsonMapper 를 주입받지 않고 직접 만드는 이유는,
     * 웹 계층 직렬화 설정과 무관하게 항상 같은 방식으로 파싱하기 위해서다.
     *
     * Spring Boot 4 는 Jackson 3 를 쓴다. 패키지가 com.fasterxml.jackson 이 아니라
     * tools.jackson 이므로 임포트할 때 주의.
     */
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public OpenAiClient(@Qualifier("openAiRestClient") RestClient restClient,
                        OpenAiProperties properties) {
        this(restClient, properties, false);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OpenAiClient(@Qualifier("openAiRestClient") RestClient restClient, OpenAiProperties properties,
            @org.springframework.beans.factory.annotation.Value("${oops.openai.structured-discovery-output-enabled:true}")
            boolean structuredDiscoveryOutput) {
        this.restClient = restClient;
        this.properties = properties;
        this.structuredDiscoveryOutput = structuredDiscoveryOutput;
        // 헤더로 실제 한도를 알기 전까지 쓸 초기 속도. 낮게 시작해야 첫 몇 초에 벌칙 대기에 들어가지 않는다.
        applyRpm(properties.requestsPerMinuteOrDefault(), "설정값");
    }

    public boolean isEnabled() {
        return properties.isConfigured();
    }

    /**
     * 서버 전체에서 동시에 나가는 OpenAI 요청 수 상한. (redesign-v3 병렬 호출)
     * 여러 영상·여러 병렬 작업이 같은 계정 한도를 나눠 쓰므로 한곳에서 막는다.
     */
    private volatile java.util.concurrent.Semaphore inFlight = new java.util.concurrent.Semaphore(6, true);

    @org.springframework.beans.factory.annotation.Value("${oops.openai.max-concurrent:6}")
    void setMaxConcurrent(int maxConcurrent) {
        if (maxConcurrent < 1) throw new IllegalArgumentException("oops.openai.max-concurrent must be >= 1");
        this.inFlight = new java.util.concurrent.Semaphore(maxConcurrent, true);
    }

    /**
     * 어느 계정으로 호출하는지 시작할 때 한 번 남긴다.
     *
     * 크레딧을 특정 조직에서 받았는데 개인 계정 키로 호출하면
     * 크레딧은 안 쓰이고 한도만 낮은 상태가 된다. 그걸 바로 알아채기 위한 로그다.
     */
    @jakarta.annotation.PostConstruct
    void logAccount() {
        if (!isEnabled()) {
            log.warn("[openai] API 키가 없습니다. LLM 분석기 전체가 스킵됩니다.");
            return;
        }
        String key = properties.apiKey();
        String masked = key.length() > 8
                ? key.substring(0, 3) + "..." + key.substring(key.length() - 4) : "***";

        log.info("[openai] 키={} 조직={} 프로젝트={} 모델={}",
                masked,
                properties.hasOrganization() ? properties.organization() : "(계정 기본값)",
                properties.hasProject() ? properties.project() : "(키에 포함된 값)",
                properties.modelOrDefault());

        if (key.startsWith("sk-proj-")) {
            log.info("[openai] 프로젝트 키입니다. 키에 조직·프로젝트가 이미 포함돼 있습니다.");
        } else {
            log.warn("[openai] 프로젝트 키(sk-proj-)가 아닙니다. "
                    + "크레딧이 있는 조직이 아니라 계정 기본 조직으로 과금될 수 있습니다. "
                    + "그 경우 OPENAI_ORG_ID 를 지정하세요.");
        }
    }

    /**
     * 이 스레드에서 호출이 실패한 횟수와 마지막 사유.
     *
     * 호출이 실패해도 Optional.empty() 만 돌려주면 분석기는 빈손으로 끝나고,
     * 사용자에게는 "확인할 지점 없음" 으로 보인다.
     * "물어봤는데 없다" 와 "물어보지도 못했다" 는 전혀 다른 이야기다.
     * 파이프라인이 이 값을 읽어 coverage 에 FAILED 로 기록한다.
     *
     * 분석은 스레드마다 하나씩 도므로 스레드별로 들고 있으면 충분하다.
     */
    enum FailureCode { MODEL_REFUSAL, INCOMPLETE_RESPONSE, JSON_PARSE_FAILED, EMPTY_RESPONSE,
        RATE_LIMITED, HTTP_FAILED, CONNECTION_FAILED }
    private record FailureState(FailureCode code, String message) {}

    /**
     * 지금 어느 영상의 어느 분석기를 돌리고 있는지.
     *
     * completeAsJson 에 인자로 넘기지 않고 여기 두는 이유는,
     * 호출처가 9곳이라 하나만 빠뜨려도 그 분석기의 토큰이 조용히 누락되기 때문이다.
     * 파이프라인이 분석기를 돌리기 직전에 한 번만 세팅한다.
     */
    /**
     * 영상 한 편의 호출 집계(사용량·실패·지금 분석기). redesign-v3 에서 병렬 호출을 위해
     * 스레드마다 따로 들던 값을 이 객체 하나로 모았다. 파이프라인 스레드가 만들고,
     * 같은 영상의 병렬 작업 스레드는 {@link #withScope} 로 같은 객체를 함께 쓴다.
     */
    public static final class CallScope {
        private final long videoId;
        /** 사용량을 함께 올릴 상위 집계(영상 전체). 실패는 올리지 않는다(분석기마다 따로 판정). */
        private final CallScope parent;
        private volatile String analyzer = "-";
        private final java.util.concurrent.atomic.AtomicLongArray usage = new java.util.concurrent.atomic.AtomicLongArray(4); // calls, prompt, cached, completion
        private final java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicReference<FailureState> lastFailure = new java.util.concurrent.atomic.AtomicReference<>();

        CallScope(long videoId) {
            this(videoId, null, "-");
        }

        private CallScope(long videoId, CallScope parent, String analyzer) {
            this.videoId = videoId;
            this.parent = parent;
            this.analyzer = analyzer;
        }

        /** 분석기 하나를 따로 돌릴 때 쓰는 하위 집계. 사용량은 이 영상 전체에 합쳐지고 실패는 따로 센다. */
        public CallScope child(String analyzerKey) {
            return new CallScope(videoId, this, analyzerKey == null ? "-" : analyzerKey);
        }

        public int failures() {
            return failures.get();
        }

        public String analyzer() {
            return analyzer;
        }

        /** OpenAI 밖의 유료 호출(검색 등). 업체 → {요청 수, USD}. 영상 전체 집계에도 함께 올린다. */
        private final java.util.concurrent.ConcurrentHashMap<String, double[]> external = new java.util.concurrent.ConcurrentHashMap<>();

        public void addExternal(String provider, int requests, double usd) {
            if (requests <= 0) return;
            for (CallScope s = this; s != null; s = s.parent) {
                s.external.compute(provider, (k, v) -> {
                    double[] next = v == null ? new double[2] : v.clone();
                    next[0] += requests;
                    next[1] += usd;
                    return next;
                });
            }
        }

        /** 업체별 {요청 수, USD} 복사본 */
        public java.util.Map<String, double[]> external() {
            var copy = new java.util.TreeMap<String, double[]>();
            external.forEach((k, v) -> copy.put(k, v.clone()));
            return copy;
        }

        public java.util.Optional<String> failureReason() {
            return java.util.Optional.ofNullable(lastFailure.get()).map(FailureState::message);
        }

        void addUsage(long prompt, long cached, long completion) {
            for (CallScope s = this; s != null; s = s.parent) {
                s.usage.incrementAndGet(0);
                s.usage.addAndGet(1, prompt);
                s.usage.addAndGet(2, cached);
                s.usage.addAndGet(3, completion);
            }
        }
    }

    private static final ThreadLocal<CallScope> scope = ThreadLocal.withInitial(() -> new CallScope(0));

    /** 지금 스레드의 집계 객체. 병렬 작업에 넘길 때 쓴다. */
    public CallScope currentScope() {
        return scope.get();
    }

    /** 다른 스레드에서 같은 영상의 집계 객체로 작업을 돌린다. 끝나면 원래 상태로 되돌린다. */
    public <T> T withScope(CallScope target, java.util.function.Supplier<T> work) {
        CallScope previous = scope.get();
        scope.set(target);
        try {
            return work.get();
        } finally {
            scope.set(previous);
        }
    }

    /** 영상 분석을 시작할 때 한 번 호출한다. 이전 분석의 잔여 상태를 정리한다. */
    public void beginVideo(Long videoId) {
        scope.set(new CallScope(videoId == null ? 0 : videoId));
    }

    /**
     * 영상 분석이 끝났을 때 호출한다.
     * 스레드 풀에서 스레드를 재사용하므로 ThreadLocal 잔여 상태를 반드시 정리해야 한다.
     * 정리하지 않으면 다음 영상 분석이 이전 영상의 failureCount/reason 을 물려받는다.
     */
    public void endVideo() {
        scope.remove();
    }

    /** 분석기 하나를 돌리기 전에 호출한다. */
    public void beginAnalyzer(String analyzerKey) {
        scope.get().analyzer = analyzerKey == null ? "-" : analyzerKey;
        resetFailureTracking();
    }

    public void resetFailureTracking() {
        scope.get().failures.set(0);
        scope.get().lastFailure.set(null);
    }

    /** 이 영상에 지금까지 쓴 양. 파이프라인이 마지막에 한 줄로 정리한다. */
    /** 집계 하나(분석기별 하위 집계 포함)의 OpenAI 사용량 */
    public TokenUsage usageOf(CallScope target) {
        var u = target.usage;
        return new TokenUsage(u.get(0), u.get(1), u.get(2), u.get(3), properties.modelOrDefault(), properties.pricing());
    }

    public TokenUsage videoUsage() {
        var u = scope.get().usage;
        return new TokenUsage(u.get(0), u.get(1), u.get(2), u.get(3), properties.modelOrDefault(),
                properties.pricing());
    }

    /**
     * 토큰 사용량과 그에 따른 비용.
     *
     * 단가는 설정에서 읽는다. 코드에 박아두면 OpenAI 가 가격을 바꿨을 때
     * 숫자가 조용히 틀리기 시작하는데, 틀린 줄도 모르게 된다.
     */
    public record TokenUsage(long calls, long promptTokens, long cachedTokens,
                             long completionTokens, String model,
                             OpenAiProperties.Pricing pricing) {

        /** 캐시되지 않은 입력 토큰. 캐시된 것은 값이 다르므로 따로 센다. */
        public long freshPromptTokens() {
            return Math.max(0, promptTokens - cachedTokens);
        }

        public double costUsd() {
            return freshPromptTokens() / 1_000_000.0 * pricing.inputUsd()
                    + cachedTokens / 1_000_000.0 * pricing.cachedInputUsd()
                    + completionTokens / 1_000_000.0 * pricing.outputUsd();
        }

        public double costKrw() {
            return costUsd() * pricing.krwRate();
        }

        public boolean isEmpty() {
            return calls == 0;
        }
    }

    public int failureCount() {
        return scope.get().failures.get();
    }

    public Optional<String> failureReason() {
        return Optional.ofNullable(scope.get().lastFailure.get()).map(FailureState::message);
    }

    /** Safe diagnostic code only; never expose provider response bodies or credentials. */
    public Optional<String> failureCode() {
        return Optional.ofNullable(scope.get().lastFailure.get()).map(f -> f.code().name());
    }

    private <T> Optional<T> fail(FailureCode code, String reason) {
        scope.get().failures.incrementAndGet();
        scope.get().lastFailure.set(new FailureState(code, reason));
        return Optional.empty();
    }

    public <T> Optional<T> completeAsJson(String systemPrompt, String userPrompt, Class<T> type) {
        if (!isEnabled()) {
            log.debug("[openai] API 키가 없어 LLM 판정을 건너뜁니다.");
            return Optional.empty();
        }

        Map<String, Object> body = requestBody(systemPrompt, userPrompt, type);
        return executeJson(body, type);
    }

    /** Optional search routing: one attempt, bounded output, same usage accounting.
     * Its failure must not mark an otherwise successful mandatory review as failed. */
    public <T> Optional<T> completeRoutingAsJson(String systemPrompt, String userPrompt, Class<T> type) {
        if (!isEnabled() || Thread.currentThread().isInterrupted()) return Optional.empty();
        int priorCount = scope.get().failures.get();
        FailureState priorFailure = scope.get().lastFailure.get();
        try {
            var body = requestBody(systemPrompt, userPrompt);
            body.put("max_completion_tokens", 1024);
            return executeJson(body, type, 1);
        } finally {
            scope.get().failures.set(priorCount);
            scope.get().lastFailure.set(priorFailure);
        }
    }

    public record ImageInput(String id, long timestampMs, String jpegBase64) {}

    /** Inline JPEG only: never send local paths or fetch arbitrary external image URLs. */
    public <T> Optional<T> completeWithImagesAsJson(String systemPrompt, String userPrompt,
                                                  java.util.List<ImageInput> images, Class<T> type) {
        if (!isEnabled()) return Optional.empty();
        if (images == null || images.isEmpty() || images.size() > 3) throw new IllegalArgumentException("1..3 images required");
        var content = new java.util.ArrayList<Map<String, Object>>();
        content.add(Map.of("type", "text", "text", userPrompt));
        var ids = new java.util.HashSet<String>();
        for (var image : images) {
            if (image == null || image.id() == null || !ids.add(image.id()) || image.timestampMs() < 0
                    || image.jpegBase64() == null || image.jpegBase64().length() > 683_000)
                throw new IllegalArgumentException("Invalid image metadata");
            byte[] bytes = java.util.Base64.getDecoder().decode(image.jpegBase64());
            if (bytes.length < 2 || bytes.length > 512_000 || (bytes[0] & 255) != 255 || (bytes[1] & 255) != 216)
                throw new IllegalArgumentException("Invalid JPEG");
            content.add(Map.of("type", "text", "text", "frameId=" + image.id() + "; timestampMs=" + image.timestampMs()));
            content.add(Map.of("type", "image_url", "image_url", Map.of("url", "data:image/jpeg;base64," + image.jpegBase64(), "detail", "low")));
        }
        var body = requestBody(systemPrompt, userPrompt);
        body.put("messages", java.util.List.of(Map.of("role", "system", "content", systemPrompt + "\n" + JSON_OUTPUT_INSTRUCTION),
                Map.of("role", "user", "content", content)));
        return executeJson(body, type);
    }

    private <T> Optional<T> executeJson(Map<String, Object> body, Class<T> type) {
        return executeJson(body, type, MAX_ATTEMPTS);
    }

    private <T> Optional<T> executeJson(Map<String, Object> body, Class<T> type, int attempts) {
        // 서버 전체 동시 요청 상한. 재시도 대기 중에도 자리를 쥐고 있어 한도 초과 시 자연히 속도가 줄어든다.
        var permits = inFlight;
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        try {
            return executeJsonHoldingPermit(body, type, attempts);
        } finally {
            permits.release();
        }
    }

    private <T> Optional<T> executeJsonHoldingPermit(Map<String, Object> body, Class<T> type, int attempts) {

        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (Thread.currentThread().isInterrupted()) return Optional.empty();
            throttle();
            if (Thread.currentThread().isInterrupted()) return Optional.empty();
            try {
                var entity = restClient.post()
                        .uri("/chat/completions")
                        .body(body)
                        .retrieve()
                        .toEntity(ChatCompletionResponse.class);

                logRateLimitOnce(entity.getHeaders());
                applyRateLimitHeaders(entity.getHeaders());
                ChatCompletionResponse response = entity.getBody();
                recordUsage(response);

                if (response == null || response.choices() == null || response.choices().isEmpty()
                        || response.choices().get(0).message() == null) return fail(FailureCode.EMPTY_RESPONSE, "AI 응답이 비어 있습니다.");
                var choice = response.choices().get(0);
                if (choice.message().refusal() != null && !choice.message().refusal().isBlank()) {
                    return fail(FailureCode.MODEL_REFUSAL, "AI가 이 요청에 대한 응답을 거부했습니다.");
                }
                if ("length".equals(choice.finish_reason()) || "content_filter".equals(choice.finish_reason())) {
                    return fail(FailureCode.INCOMPLETE_RESPONSE, "AI 응답이 완성되기 전에 중단되었습니다.");
                }

                String content = Optional.ofNullable(response)
                        .map(ChatCompletionResponse::choices)
                        .filter(choices -> !choices.isEmpty())
                        .map(choices -> choices.get(0).message().content())
                        .orElse(null);

                if (content == null || content.isBlank()) {
                    return fail(FailureCode.EMPTY_RESPONSE, "AI 응답 본문이 비어 있습니다.");
                }
                return Optional.of(jsonMapper.readValue(content, type));

            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();

                // 429 = 요청 한도 초과. 잠시 기다렸다 다시 시도한다.
                // 여기서 그냥 포기하면 분석기가 빈손으로 돌아가고,
                // 사용자에게는 '논란 없음' 으로 보인다. 실제로는 물어보지도 못한 것이다.
                if (status == 429) {
                    String errorBody = e.getResponseBodyAsString();
                    // 잔액·결제 문제도 429 로 온다. 기다려도 풀리지 않으므로 바로 실패로 적는다.
                    if (errorBody != null && errorBody.contains("insufficient_quota")) {
                        log.error("[openai] 속도 문제가 아니라 잔액 또는 결제 문제입니다. "
                                + "https://platform.openai.com/settings/organization/billing 확인. 응답: {}", abbreviate(errorBody));
                        return fail(FailureCode.HTTP_FAILED, "AI 계정의 잔액이 부족해 이 단계를 수행하지 못했습니다.");
                    }
                    long waitMs = waitMillis(e, attempt);
                    // 한 번 맞았으면 지금 속도가 한도보다 빠르다. 앞으로의 속도도 늦춘다.
                    slowDown();
                    applyRateLimitHeaders(e.getResponseHeaders());

                    // 수십 분을 기다리라는 것은 일일 한도를 다 썼다는 뜻이다.
                    // 기다려봐야 의미가 없으므로 바로 포기한다.
                    if (waitMs > MAX_WAIT_MS) {
                        log.error("[openai] 일일 요청 한도를 소진한 것으로 보입니다. "
                                + "다시 시도하려면 약 {}분을 기다려야 합니다. "
                                + "분석 결과가 비어 있을 수 있습니다. "
                                + "https://platform.openai.com/settings/organization/limits 확인{}",
                                waitMs / 60000, limitDiagnosis(errorBody));
                        log.error("[openai] 응답 원문 — {} · 한도 헤더 — {}", abbreviate(errorBody), limitHeaders(e.getResponseHeaders()));
                        return fail(FailureCode.RATE_LIMITED, "AI 요청 한도를 모두 써서 이 단계를 수행하지 못했습니다.");
                    }

                    if (attempt < attempts) {
                        log.warn("[openai] 요청 한도 초과. {}초 후 재시도 ({}/{}) · 간격을 {}ms 로 늘렸습니다",
                                waitMs / 1000, attempt, MAX_ATTEMPTS, minIntervalMs.get());
                        sleep(waitMs);
                        continue;
                    }

                    log.error("[openai] 요청 한도 초과로 포기했습니다.{} 응답 원문 — {}",
                            limitDiagnosis(errorBody), abbreviate(errorBody));
                    return fail(FailureCode.RATE_LIMITED, "AI 요청 한도 초과로 이 단계를 수행하지 못했습니다.");
                }

                {
                    log.warn("[openai] 호출 실패 HTTP {} : {}", status,
                            abbreviate(e.getResponseBodyAsString()));
                }
                return fail(FailureCode.HTTP_FAILED, "AI 호출이 실패했습니다 (HTTP %d).".formatted(status));

            } catch (RestClientException e) {
                log.warn("[openai] 호출 실패: {}", e.getMessage());
                return fail(FailureCode.CONNECTION_FAILED, "AI 서버에 연결하지 못했습니다.");
            } catch (Exception e) {
                log.warn("[openai] 응답 파싱 실패: {}", e.getMessage());
                return fail(FailureCode.JSON_PARSE_FAILED, "AI 응답을 해석하지 못했습니다.");
            }
        }
        return Optional.empty();
    }

    /** Keep the existing endpoint and JSON contract; avoid unsupported sampling parameters with reasoning. */
    public Map<String, Object> replayRequestBody(String systemPrompt, String userPrompt) {
        // Body only: no RestClient, API key, organization/project headers or endpoint credentials.
        return Map.copyOf(requestBody(systemPrompt, userPrompt));
    }

    /** Typed replay includes the same opt-in response contract as the actual text request. */
    public Map<String, Object> replayRequestBody(String systemPrompt, String userPrompt, Class<?> type) {
        return Map.copyOf(requestBody(systemPrompt, userPrompt, type));
    }

    private Map<String, Object> requestBody(String systemPrompt, String userPrompt, Class<?> type) {
        var body = requestBody(systemPrompt, userPrompt);
        if (structuredDiscoveryOutput) body.put("response_format", JsonSchemaResponseFormat.forType(type, userPrompt));
        return body;
    }

    Map<String, Object> requestBody(String systemPrompt, String userPrompt) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", properties.modelOrDefault());
        if (properties.isLuna()) {
            String effort = properties.reasoningEffortOrDefault();
            body.put("reasoning_effort", effort);
            if ("none".equals(effort)) body.put("temperature", 0.1);
        } else {
            body.put("temperature", 0.1);
        }
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(Map.of("role", "system", "content", systemPrompt + "\n" + JSON_OUTPUT_INSTRUCTION),
                Map.of("role", "user", "content", userPrompt)));
        return body;
    }

    /**
     * 호출 하나가 쓴 토큰을 기록한다.
     *
     * 한 줄씩 남기는 이유는 어느 분석기가 비싼지 보기 위해서다.
     * 영상 하나에 호출이 수십 번 나가는데, 총합만 보면
     * 대본이 긴 게 문제인지 분석기 하나가 유독 큰지 알 수 없다.
     */
    private void recordUsage(ChatCompletionResponse response) {
        if (response == null || response.usage() == null) {
            return;
        }
        var usage = response.usage();
        long cached = usage.prompt_tokens_details() == null
                ? 0 : usage.prompt_tokens_details().cached_tokens();

        scope.get().addUsage(usage.prompt_tokens(), cached, usage.completion_tokens());

        // 호출당 평균 토큰이 추정치와 많이 다르면 간격을 다시 잡는다. 토큰 한도가 먼저 마르는 경우가 흔하다.
        long calls = measuredCalls.incrementAndGet();
        long tokens = measuredTokens.addAndGet(usage.prompt_tokens() + usage.completion_tokens());
        long avg = Math.max(500, tokens / calls);
        long before = avgTokensPerCall.getAndSet(avg);
        if (knownRpm.get() > 0 && Math.abs(avg - before) > before * 0.3) {
            applyRpm((int) knownRpm.get(), "토큰 재측정");
        }

        log.info("[openai-usage] videoId={} analyzer={} model={} input={} cached={} output={} total={}",
                scope.get().videoId,
                scope.get().analyzer,
                response.model(),
                usage.prompt_tokens(),
                cached,
                usage.completion_tokens(),
                usage.total_tokens());
    }

    /**
     * OpenAI 가 응답 헤더로 알려주는 실제 한도를 기록한다.
     *
     * 크레딧이 남아 있어도 요청 한도는 별개다.
     * 한도는 계정 등급(usage tier)이 정하고, 등급은 누적 결제액으로 올라간다.
     * 여기 찍히는 숫자가 우리 계정의 진짜 상한이다.
     */
    private void logRateLimitOnce(org.springframework.http.HttpHeaders headers) {
        if (headers == null || !limitLogged.compareAndSet(false, true)) {
            return;
        }
        String requestsPerMin = headers.getFirst("x-ratelimit-limit-requests");
        String tokensPerMin = headers.getFirst("x-ratelimit-limit-tokens");

        if (requestsPerMin == null && tokensPerMin == null) {
            log.info("[openai] 한도 헤더를 받지 못했습니다.");
            return;
        }
        log.info("[openai] 계정 한도 — 분당 요청 {}건 / 분당 토큰 {} (모델 {})",
                requestsPerMin, tokensPerMin, properties.modelOrDefault());

        try {
            if (requestsPerMin != null && Integer.parseInt(requestsPerMin) < 60) {
                log.warn("[openai] 분당 요청 한도가 낮습니다({}). "
                        + "영상 하나에 수십 번 호출하므로 분석이 자주 끊길 수 있습니다. "
                        + "https://platform.openai.com/settings/organization/limits 에서 등급을 확인하세요.",
                        requestsPerMin);
            }
        } catch (NumberFormatException ignored) {
            // 헤더 형식이 예상과 다르면 넘어간다
        }
    }

    /**
     * 응답 헤더를 보고 보내는 속도를 계정 한도에 맞춘다. 남은 요청·토큰이 바닥이면 창이 열릴 때까지 쉰다.
     */
    private void applyRateLimitHeaders(org.springframework.http.HttpHeaders headers) {
        if (headers == null) {
            return;
        }

        Long tokenLimit = parseLong(headers.getFirst("x-ratelimit-limit-tokens"));
        if (tokenLimit != null && tokenLimit > 0) {
            knownTpm.set(tokenLimit);
        }

        Long limit = parseLong(headers.getFirst("x-ratelimit-limit-requests"));
        if (limit != null && limit > 0 && knownRpm.get() != limit) {
            applyRpm(limit.intValue(), "계정 한도");
            log.info("[openai] 계정 한도 — 분당 요청 {}건 / 분당 토큰 {} (모델 {})",
                    limit, tokenLimit == null ? "?" : tokenLimit, properties.modelOrDefault());
        }

        // 남은 토큰이 바닥이면 창이 열릴 때까지 쉰다.
        // 요청 수는 남아 있는데 토큰이 먼저 마르는 경우가 실제로 더 흔하다.
        Long remainingTokens = parseLong(headers.getFirst("x-ratelimit-remaining-tokens"));
        if (remainingTokens != null && remainingTokens < avgTokensPerCall.get() * 2) {
            long resetMs = parseDurationMs(headers.getFirst("x-ratelimit-reset-tokens"));
            if (resetMs > 0 && resetMs <= MAX_WAIT_MS) {
                log.info("[openai] 분당 토큰이 거의 다 찼습니다(남은 {}). {}초 쉬어갑니다.",
                        remainingTokens, Math.max(1, resetMs / 1000));
                reserveAfter(resetMs + 500);
            }
        }

        // 남은 횟수가 바닥이면 창이 새로 열릴 때까지 쉰다.
        // 여기서 한 박자 쉬는 게 429 를 맞고 28분 기다리는 것보다 훨씬 낫다.
        Long remaining = parseLong(headers.getFirst("x-ratelimit-remaining-requests"));
        if (remaining != null && remaining <= 1) {
            long resetMs = parseDurationMs(headers.getFirst("x-ratelimit-reset-requests"));
            if (resetMs > 0 && resetMs <= MAX_WAIT_MS) {
                log.info("[openai] 분당 허용량을 거의 다 썼습니다(남은 {}건). {}초 쉬어갑니다.",
                        remaining, Math.max(1, resetMs / 1000));
                reserveAfter(resetMs + 500);
            }
        }
    }

    /**
     * 429 응답에서 어떤 한도(RPM·RPD·TPM)에 걸렸는지 읽어 사람이 읽을 문장으로 만든다.
     */
    static String limitDiagnosis(String body) {
        if (body == null || body.isBlank()) {
            return " (응답 본문이 없어 어떤 한도인지 알 수 없습니다.)";
        }
        String lower = body.toLowerCase(java.util.Locale.ROOT);

        if (lower.contains("per day") || lower.contains("rpd")) {
            return " **오늘 쓸 수 있는 요청 수(RPD)를 다 썼습니다.** "
                    + "간격을 늘려도 해결되지 않습니다. 한도가 초기화될 때까지 기다리거나 "
                    + "계정 등급을 올려야 합니다. "
                    + "https://platform.openai.com/settings/organization/limits";
        }
        if (lower.contains("tokens per min") || lower.contains("tpm")) {
            return " **분당 토큰 한도(TPM)** 입니다. 요청 횟수가 아니라 프롬프트 크기 문제입니다. "
                    + "맥락 확인 창 크기(oops.context.discovery-max-lines)나 동시 호출 수(oops.openai.max-concurrent)를 줄여야 합니다.";
        }
        if (lower.contains("per min") || lower.contains("rpm")) {
            return " **분당 요청 한도(RPM)** 입니다. oops.openai.requests-per-minute 를 낮추세요. "
                    + "현재 간격 " + minIntervalMs.get() + "ms"
                    + (knownRpm.get() > 0 ? " · 계정 한도 분당 " + knownRpm.get() + "건" : "");
        }
        return " (메시지에 한도 종류가 없습니다. 아래 원문을 확인하세요.)";
    }

    /**
     * 한도 관련 헤더를 한 줄로 모은다.
     *
     * remaining 이 남아 있는데 429 가 왔다면 분당 한도 문제가 아니다.
     * 그 한 줄이 원인을 가른다.
     */
    private String limitHeaders(org.springframework.http.HttpHeaders headers) {
        if (headers == null) {
            return "(없음)";
        }
        return "요청 %s/%s (초기화 %s) · 토큰 %s/%s (초기화 %s)".formatted(
                headers.getFirst("x-ratelimit-remaining-requests"),
                headers.getFirst("x-ratelimit-limit-requests"),
                headers.getFirst("x-ratelimit-reset-requests"),
                headers.getFirst("x-ratelimit-remaining-tokens"),
                headers.getFirst("x-ratelimit-limit-tokens"),
                headers.getFirst("x-ratelimit-reset-tokens"));
    }

    /**
     * 분당 요청 수를 간격으로 바꿔 적용한다.
     *
     * **요청 수만 보면 안 된다. 분당 토큰이 먼저 걸린다.**
     *
     * 계정 한도가 분당 1만 건이라고 해도 분당 토큰이 20만이면,
     * 호출 하나가 3천 토큰을 쓰는 우리 경우 실제 상한은 분당 66건이다.
     * 요청 수만 보고 간격을 7ms 로 잡으면 분당 8500건 속도가 되어
     * 토큰 쪽에서 곧바로 막힌다. 그러면 "분당 1만 건인데 왜 걸리지" 가 된다.
     *
     * 그래서 둘 다 계산해서 **느린 쪽**을 쓴다.
     */
    private void applyRpm(int rpm, String source) {
        knownRpm.set(rpm);

        long byRequests = (long) Math.ceil(60_000.0 / (Math.max(1, rpm) * HEADROOM));

        long tpm = knownTpm.get();
        long perCall = Math.max(1, avgTokensPerCall.get());
        long byTokens = tpm <= 0 ? 0
                : (long) Math.ceil(60_000.0 / (Math.max(1, tpm / (double) perCall) * HEADROOM));

        long interval = Math.max(byRequests, byTokens);
        minIntervalMs.set(interval);

        if (byTokens > byRequests) {
            log.info("[openai] 호출 속도 — 분당 {}건 ({}) 이지만 분당 토큰 {} 이 먼저 걸려 "
                            + "간격 {}ms (약 분당 {}건)",
                    rpm, source, tpm, interval, 60_000 / Math.max(1, interval));
        } else {
            log.info("[openai] 호출 속도 — 분당 {}건 ({}) → 요청 간격 {}ms", rpm, source, interval);
        }
    }

    /**
     * 429 를 맞았으면 앞으로의 속도를 절반으로 줄인다.
     *
     * 재시도만 하고 속도를 그대로 두면 같은 벽에 다시 부딪힌다.
     * 한 번 겪은 것에서 배워야 다음 분석기가 살아남는다.
     */
    private void slowDown() {
        long doubled = Math.min(minIntervalMs.get() * 2, 30_000);
        minIntervalMs.set(doubled);
    }

    /**
     * 다음 차례를 받아 그때까지 기다린다. 순번을 미리 잡아 여러 스레드가 동시에 나가지 않게 한다.
     */
    private void throttle() {
        long interval = minIntervalMs.get();
        while (true) {
            long booked = nextSlotAt.get();
            long now = System.currentTimeMillis();
            long mySlot = Math.max(now, booked);

            if (nextSlotAt.compareAndSet(booked, mySlot + interval)) {
                long wait = mySlot - now;
                if (wait > 0) {
                    // 실제로 쉬고 있는지 확인할 수 있어야 한다.
                    // 간격을 설정해 놓고 안 지켜지는 경우가 있었는데,
                    // 로그가 없으면 "간격은 늘렸는데 왜 또 걸리지" 로 한참 헤맨다.
                    log.debug("[openai] 다음 호출까지 {}ms 대기 (간격 {}ms)", wait, interval);
                    sleep(wait);
                }
                return;
            }
            // 다른 스레드가 먼저 잡았다. 다시 읽고 재시도한다.
        }
    }

    /** 지금부터 이만큼 뒤로 다음 차례를 미룬다. */
    private void reserveAfter(long delayMs) {
        long target = System.currentTimeMillis() + delayMs;
        nextSlotAt.updateAndGet(current -> Math.max(current, target));
    }

    static Long parseLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * "1m30s", "6ms", "2.5s" 형태를 밀리초로 바꾼다.
     *
     * OpenAI 의 reset 헤더는 단위를 붙여서 준다.
     * 형식이 한 가지가 아니라 조각을 다 더한다.
     */
    static long parseDurationMs(String value) {
        if (value == null || value.isBlank()) return 0;

        Matcher m = DURATION_PART.matcher(value.trim());
        long total = 0;
        while (m.find()) {
            double amount = Double.parseDouble(m.group(1));
            total += switch (m.group(2)) {
                case "ms" -> (long) amount;
                case "s" -> (long) (amount * 1000);
                case "m" -> (long) (amount * 60_000);
                case "h" -> (long) (amount * 3_600_000);
                default -> 0L;
            };
        }
        return total;
    }

    /**
     * 얼마나 기다릴지 정한다.
     * OpenAI 가 Retry-After 헤더나 "try again in 6s" 안내를 주면 그걸 따르고,
     * 없으면 시도할수록 간격을 늘린다.
     */
    private long waitMillis(RestClientResponseException e, int attempt) {
        String retryAfter = e.getResponseHeaders() == null
                ? null : e.getResponseHeaders().getFirst("Retry-After");
        if (retryAfter != null) {
            try {
                return Math.max(1000L, (long) (Double.parseDouble(retryAfter.trim()) * 1000));
            } catch (NumberFormatException ignored) {
                // 헤더가 날짜 형식일 수도 있다. 아래 기본값을 쓴다.
            }
        }

        Matcher m = RETRY_HINT.matcher(e.getResponseBodyAsString());
        if (m.find()) {
            double value = Double.parseDouble(m.group(1));
            long ms = "ms".equalsIgnoreCase(m.group(2)) ? (long) value : (long) (value * 1000);
            return Math.max(1000L, ms + 500);   // 안내값보다 살짝 더 기다린다
        }

        return Duration.ofSeconds(2L * attempt * attempt).toMillis();   // 2s, 8s, 18s
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private String abbreviate(String text) {
        if (text == null) return "";
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    /**
     * OpenAI 응답. usage 를 같이 읽어서 토큰 사용량을 기록한다.
     *
     * 필드 이름이 스네이크 케이스인 이유는 OpenAI 가 그렇게 주기 때문이다.
     * 애노테이션으로 매핑할 수도 있지만, 이 record 는 응답을 그대로 받는 용도라
     * 원본 이름을 유지하는 편이 대조하기 쉽다.
     */
    record ChatCompletionResponse(String model, List<Choice> choices, Usage usage) {

        record Choice(Message message, String finish_reason) {}

        record Message(String content, String refusal) {}

        record Usage(long prompt_tokens,
                     long completion_tokens,
                     long total_tokens,
                     PromptTokensDetails prompt_tokens_details) {}

        /** 같은 프롬프트를 반복해서 보내면 일부가 캐시된다. 캐시된 입력은 절반 값이다. */
        record PromptTokensDetails(long cached_tokens) {}
    }
}
