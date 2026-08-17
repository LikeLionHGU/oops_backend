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

    /**
     * 요청 사이 최소 간격.
     * 분석기 5개가 각자 여러 번 호출하므로 한꺼번에 몰리면 한도에 걸린다.
     * 계정 등급이 낮으면 분당 허용량이 매우 적다.
     */
    private static final long MIN_INTERVAL_MS = 250;

    /** "try again in 6s" 같은 안내에서 대기 시간을 뽑아낸다. */
    private static final Pattern RETRY_HINT =
            Pattern.compile("try again in ([0-9.]+)(ms|s)", Pattern.CASE_INSENSITIVE);

    /** 여러 분석 스레드가 공유한다. 마지막 호출 시각. */
    private static final AtomicLong lastCallAt = new AtomicLong(0);

    /** 계정의 실제 한도를 한 번만 찍는다. 매 호출마다 찍으면 로그가 지저분해진다. */
    private static final java.util.concurrent.atomic.AtomicBoolean limitLogged =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final RestClient restClient;
    private final OpenAiProperties properties;

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
        this.restClient = restClient;
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.isConfigured();
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
        String masked = key.length() > 12
                ? key.substring(0, 8) + "..." + key.substring(key.length() - 4) : "***";

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
    private static final ThreadLocal<int[]> failureCount = ThreadLocal.withInitial(() -> new int[1]);
    private static final ThreadLocal<String> failureReason = new ThreadLocal<>();

    /**
     * 지금 어느 영상의 어느 분석기를 돌리고 있는지.
     *
     * completeAsJson 에 인자로 넘기지 않고 여기 두는 이유는,
     * 호출처가 9곳이라 하나만 빠뜨려도 그 분석기의 토큰이 조용히 누락되기 때문이다.
     * 파이프라인이 분석기를 돌리기 직전에 한 번만 세팅한다.
     */
    private static final ThreadLocal<long[]> currentVideoId = ThreadLocal.withInitial(() -> new long[]{0});
    private static final ThreadLocal<String> currentAnalyzer = ThreadLocal.withInitial(() -> "-");

    /** 영상 한 편에 쓴 토큰 누적치 */
    private static final ThreadLocal<long[]> usageTotals =
            ThreadLocal.withInitial(() -> new long[4]);   // calls, prompt, cached, completion

    /** 영상 분석을 시작할 때 한 번 호출한다. */
    public void beginVideo(Long videoId) {
        currentVideoId.get()[0] = videoId == null ? 0 : videoId;
        usageTotals.set(new long[4]);
    }

    /** 분석기 하나를 돌리기 전에 호출한다. */
    public void beginAnalyzer(String analyzerKey) {
        currentAnalyzer.set(analyzerKey == null ? "-" : analyzerKey);
        resetFailureTracking();
    }

    public void resetFailureTracking() {
        failureCount.get()[0] = 0;
        failureReason.remove();
    }

    /** 이 영상에 지금까지 쓴 양. 파이프라인이 마지막에 한 줄로 정리한다. */
    public TokenUsage videoUsage() {
        long[] u = usageTotals.get();
        return new TokenUsage(u[0], u[1], u[2], u[3], properties.modelOrDefault(),
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
        return failureCount.get()[0];
    }

    public Optional<String> failureReason() {
        return Optional.ofNullable(failureReason.get());
    }

    private <T> Optional<T> fail(String reason) {
        failureCount.get()[0]++;
        failureReason.set(reason);
        return Optional.empty();
    }

    public <T> Optional<T> completeAsJson(String systemPrompt, String userPrompt, Class<T> type) {
        if (!isEnabled()) {
            log.debug("[openai] API 키가 없어 LLM 판정을 건너뜁니다.");
            return Optional.empty();
        }

        Map<String, Object> body = Map.of(
                "model", properties.modelOrDefault(),
                "temperature", 0.1,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            throttle();
            try {
                var entity = restClient.post()
                        .uri("/chat/completions")
                        .body(body)
                        .retrieve()
                        .toEntity(ChatCompletionResponse.class);

                logRateLimitOnce(entity.getHeaders());
                ChatCompletionResponse response = entity.getBody();
                recordUsage(response);

                String content = Optional.ofNullable(response)
                        .map(ChatCompletionResponse::choices)
                        .filter(choices -> !choices.isEmpty())
                        .map(choices -> choices.get(0).message().content())
                        .orElse(null);

                if (content == null || content.isBlank()) {
                    return Optional.empty();
                }
                return Optional.of(jsonMapper.readValue(content, type));

            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();

                // 429 = 요청 한도 초과. 잠시 기다렸다 다시 시도한다.
                // 여기서 그냥 포기하면 분석기가 빈손으로 돌아가고,
                // 사용자에게는 '논란 없음' 으로 보인다. 실제로는 물어보지도 못한 것이다.
                if (status == 429) {
                    long waitMs = waitMillis(e, attempt);

                    // 수십 분을 기다리라는 것은 일일 한도를 다 썼다는 뜻이다.
                    // 기다려봐야 의미가 없으므로 바로 포기한다.
                    if (waitMs > MAX_WAIT_MS) {
                        log.error("[openai] 일일 요청 한도를 소진한 것으로 보입니다. "
                                + "다시 시도하려면 약 {}분을 기다려야 합니다. "
                                + "분석 결과가 비어 있을 수 있습니다. "
                                + "https://platform.openai.com/settings/organization/limits 확인",
                                waitMs / 60000);
                        return fail("AI 요청 한도를 모두 써서 이 단계를 수행하지 못했습니다.");
                    }

                    if (attempt < MAX_ATTEMPTS) {
                        log.warn("[openai] 요청 한도 초과. {}초 후 재시도 ({}/{})",
                                waitMs / 1000, attempt, MAX_ATTEMPTS);
                        sleep(waitMs);
                        continue;
                    }

                    log.error("[openai] 요청 한도 초과로 포기했습니다. "
                            + "분석 결과가 비어 있을 수 있습니다.");
                    return fail("AI 요청 한도 초과로 이 단계를 수행하지 못했습니다.");
                }

                {
                    log.warn("[openai] 호출 실패 HTTP {} : {}", status,
                            abbreviate(e.getResponseBodyAsString()));
                }
                return fail("AI 호출이 실패했습니다 (HTTP %d).".formatted(status));

            } catch (RestClientException e) {
                log.warn("[openai] 호출 실패: {}", e.getMessage());
                return fail("AI 서버에 연결하지 못했습니다.");
            } catch (Exception e) {
                log.warn("[openai] 응답 파싱 실패: {}", e.getMessage());
                return fail("AI 응답을 해석하지 못했습니다.");
            }
        }
        return Optional.empty();
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

        long[] total = usageTotals.get();
        total[0]++;
        total[1] += usage.prompt_tokens();
        total[2] += cached;
        total[3] += usage.completion_tokens();

        log.info("[openai-usage] videoId={} analyzer={} model={} input={} cached={} output={} total={}",
                currentVideoId.get()[0],
                currentAnalyzer.get(),
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

    /** 요청이 한꺼번에 몰리지 않게 최소 간격을 둔다. */
    private void throttle() {
        long gap = System.currentTimeMillis() - lastCallAt.get();
        if (gap < MIN_INTERVAL_MS) {
            sleep(MIN_INTERVAL_MS - gap);
        }
        lastCallAt.set(System.currentTimeMillis());
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

        record Choice(Message message) {}

        record Message(String content) {}

        record Usage(long prompt_tokens,
                     long completion_tokens,
                     long total_tokens,
                     PromptTokensDetails prompt_tokens_details) {}

        /** 같은 프롬프트를 반복해서 보내면 일부가 캐시된다. 캐시된 입력은 절반 값이다. */
        record PromptTokensDetails(long cached_tokens) {}
    }
}
