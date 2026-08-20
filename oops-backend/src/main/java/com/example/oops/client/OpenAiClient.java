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
     * 429 를 맞으면 OpenAI 가 얼마 뒤에 오라고 알려주는데, 그게 수십 분일 때가 있다.
     * 그만큼 기다리는 건 멈춘 것과 같으므로 상한을 넘으면 포기하고
     * 부분 결과라도 돌려준다.
     *
     * **다만 여기까지 왔다는 건 이미 늦은 것이다.**
     * 429 는 맞고 나서 대응할 게 아니라 맞지 않게 보내는 문제다.
     * 실제 방어는 throttle() 과 헤더 기반 선제 대기가 한다.
     */
    private static final long MAX_WAIT_MS = 60_000;

    /**
     * 한도 대비 여유분.
     *
     * 분당 10건이라고 정확히 10건을 보내면 경계에서 걸린다.
     * 서버와 우리 시계가 다르고, 창(window)이 밀리는 순간이 있다.
     * 90% 만 쓴다.
     */
    private static final double HEADROOM = 0.9;

    /** "try again in 6s" 같은 안내에서 대기 시간을 뽑아낸다. */
    private static final Pattern RETRY_HINT =
            Pattern.compile("try again in ([0-9.]+)(ms|s)", Pattern.CASE_INSENSITIVE);

    /** "1m30s", "6ms", "2.5s" 같은 헤더 값을 밀리초로 읽는다. */
    private static final Pattern DURATION_PART =
            Pattern.compile("([0-9.]+)(ms|s|m|h)");

    /**
     * 다음 요청을 보낼 수 있는 가장 이른 시각.
     *
     * "마지막 호출 시각" 이 아니라 "다음 차례" 를 들고 있는 이유는,
     * 여러 스레드가 동시에 들어와도 순번이 겹치지 않게 하기 위해서다.
     * 마지막 시각만 보면 두 스레드가 같은 값을 읽고 둘 다 조금만 기다린 뒤
     * 동시에 나가버린다. 그게 429 의 흔한 원인이다.
     */
    private static final AtomicLong nextSlotAt = new AtomicLong(0);

    /**
     * 요청 사이 최소 간격. 계정 한도에 맞춰 실행 중에 조정된다.
     *
     * 예전에는 250ms 고정이었다. 이론상 분당 240건인데 계정 한도가
     * 분당 10건이면 처음 몇 초 만에 한도를 넘기고, 그 뒤로는
     * OpenAI 가 28분 뒤에 오라고 답한다. 그 영상은 결과가 통째로 빈다.
     */
    private static final AtomicLong minIntervalMs = new AtomicLong(6_000);

    /** 헤더에서 읽은 계정 한도(분당 요청). 아직 모르면 0 */
    private static final AtomicLong knownRpm = new AtomicLong(0);

    /** 헤더에서 읽은 계정 한도(분당 토큰). 아직 모르면 0 */
    private static final AtomicLong knownTpm = new AtomicLong(0);

    /**
     * 호출 하나가 쓰는 평균 토큰. 분당 토큰 한도를 간격으로 바꾸는 데 쓴다.
     *
     * 첫 호출 전에는 알 수 없으므로 넉넉하게 잡아둔다.
     * 적게 잡으면 처음 몇 번이 너무 빨리 나가서 토큰 한도를 넘긴다.
     */
    private static final AtomicLong avgTokensPerCall = new AtomicLong(3_000);

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

        // 헤더로 실제 한도를 알기 전까지 쓸 초기 속도.
        // 여기서 낮게 시작하는 게 중요하다. 첫 몇 초에 몰아 보내면
        // 그 한 번으로 계정이 벌칙 대기에 들어가 그 영상은 결과가 빈다.
        applyRpm(properties.requestsPerMinuteOrDefault(), "설정값");

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

    /**
     * 이 영상에서 실제로 보낸 요청 수. {보낸 수, 429 로 거절당한 수}
     *
     * 토큰 사용량과 따로 세는 이유는 **한도가 깎이는 기준이 다르기 때문**이다.
     * 비용은 응답을 받은 호출에만 붙지만, 요청 한도는 거절당한 요청도 깎는다.
     * 성공한 호출만 세면 "호출 1회 했는데 왜 한도에 걸리지" 가 된다.
     */
    private static final ThreadLocal<long[]> requestCounts =
            ThreadLocal.withInitial(() -> new long[2]);   // requests, rateLimited

    /**
     * 분석기별 요청 수. 60분 환산을 제대로 하려면 이게 필요하다.
     *
     * 총합만 알면 "1분에 7.8건이니 60분이면 468건" 같은 계산을 하게 되는데,
     * 대부분의 분석기는 상한이 걸려 있어서 길어져도 호출이 안 는다.
     * 나뉘어 있어야 늘어나는 것만 곱할 수 있다.
     */
    private static final ThreadLocal<java.util.Map<String, Long>> requestsByAnalyzer =
            ThreadLocal.withInitial(java.util.LinkedHashMap::new);

    /** 영상 하나가 시작될 때 호출. 사용량과 요청 수를 처음부터 다시 센다. */
    public void beginVideo(Long videoId) {
        requestCounts.set(new long[2]);
        requestsByAnalyzer.set(new java.util.LinkedHashMap<>());
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

    /** 분석기별로 요청이 몇 건 나갔는지. 60분 환산에 쓴다. */
    public java.util.Map<String, Long> requestsByAnalyzer() {
        return java.util.Map.copyOf(requestsByAnalyzer.get());
    }

    /** 이 영상에 지금까지 쓴 양. 파이프라인이 마지막에 한 줄로 정리한다. */
    public TokenUsage videoUsage() {
        long[] u = usageTotals.get();
        long[] r = requestCounts.get();
        return new TokenUsage(u[0], u[1], u[2], u[3], r[0], r[1],
                properties.modelOrDefault(), properties.pricing());
    }

    /**
     * 토큰 사용량과 그에 따른 비용.
     *
     * 단가는 설정에서 읽는다. 코드에 박아두면 OpenAI 가 가격을 바꿨을 때
     * 숫자가 조용히 틀리기 시작하는데, 틀린 줄도 모르게 된다.
     *
     * @param calls        응답을 정상적으로 받은 호출 수. **비용은 이것만 발생한다**
     * @param requests     실제로 보낸 요청 수. **요청 한도는 이것으로 깎인다**
     * @param rateLimited  429 로 거절당한 요청 수
     */
    public record TokenUsage(long calls, long promptTokens, long cachedTokens,
                             long completionTokens, long requests, long rateLimited,
                             String model, OpenAiProperties.Pricing pricing) {

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
            // 거절당해도 한도는 깎인다. 보내기 전에 센다.
            requestCounts.get()[0]++;
            requestsByAnalyzer.get().merge(currentAnalyzer.get(), 1L, Long::sum);
            try {
                var entity = restClient.post()
                        .uri("/chat/completions")
                        .body(body)
                        .retrieve()
                        .toEntity(ChatCompletionResponse.class);

                applyRateLimitHeaders(entity.getHeaders());
                ChatCompletionResponse response = entity.getBody();
                recordUsage(response);

                String content = Optional.ofNullable(response)
                        .map(ChatCompletionResponse::choices)
                        .filter(choices -> !choices.isEmpty())
                        .map(choices -> choices.get(0).message().content())
                        .orElse(null);

                if (content == null || content.isBlank()) {
                    // 200 인데 내용이 없다. 거절·내용 필터·잘린 응답이 여기로 온다.
                    //
                    // 그냥 empty 를 돌리면 실패 횟수가 안 올라간다.
                    // 그러면 파이프라인이 이 분석기를 SUCCESS 로 적고,
                    // 리포트는 warnings 없이 '확인할 지점 0곳' 으로 나간다.
                    // 사용자에게는 "봤는데 없다" 로 읽히지만 못 본 것이다.
                    return fail("AI 가 응답 내용을 돌려주지 않았습니다.");
                }
                return Optional.of(jsonMapper.readValue(content, type));

            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();

                // 429 = 요청 한도 초과. 잠시 기다렸다 다시 시도한다.
                // 여기서 그냥 포기하면 분석기가 빈손으로 돌아가고,
                // 사용자에게는 '논란 없음' 으로 보인다. 실제로는 물어보지도 못한 것이다.
                if (status == 429) {
                    requestCounts.get()[1]++;
                    // 이름을 errorBody 로 두는 이유 — 위에 요청 본문 body 가 이미 있다
                    String errorBody = e.getResponseBodyAsString();

                    // **429 가 전부 "너무 빨라서" 는 아니다.**
                    //
                    // 잔액이 없거나 결제 수단에 문제가 있어도 429 로 온다.
                    // 그건 기다린다고 풀리지 않는다. 계정에서 해결해야 한다.
                    // 속도 문제로 오해하면 간격만 계속 늘리다가 분석을 통째로 날린다.
                    if (errorBody.contains("insufficient_quota")) {
                        log.error("[openai] 속도 문제가 아닙니다. **잔액 또는 결제 문제**입니다. "
                                + "간격을 늘려도 풀리지 않습니다. "
                                + "https://platform.openai.com/settings/organization/billing 에서 "
                                + "잔액과, 크레딧이 붙어 있는 조직으로 호출하고 있는지 확인하세요. "
                                + "응답: {}", abbreviate(errorBody));
                        return fail("AI 계정의 잔액이 부족해 이 단계를 수행하지 못했습니다.");
                    }

                    long waitMs = waitMillis(e, attempt);

                    // 한 번 맞았으면 지금 속도가 한도보다 빠르다는 뜻이다.
                    // 이 요청만 다시 보내는 게 아니라 앞으로의 속도를 늦춘다.
                    // 안 그러면 재시도도 같은 속도로 나가서 또 맞는다.
                    slowDown();
                    applyRateLimitHeaders(e.getResponseHeaders());

                    if (waitMs > MAX_WAIT_MS) {
                        // **응답 원문을 반드시 남긴다.**
                        //
                        // 어떤 한도에 걸렸는지는 OpenAI 가 메시지에 적어준다.
                        //   "requests per min (RPM)"  → 우리가 너무 빨리 보냈다. 간격 문제
                        //   "requests per day (RPD)"  → 오늘 할당량을 다 썼다. 내일까지 못 쓴다
                        //   "tokens per min (TPM)"    → 프롬프트가 크다. 창 크기 문제
                        //
                        // 이걸 안 찍으면 셋 중 무엇인지 모른 채 간격만 만지게 된다.
                        // 실제로 그렇게 한참을 헤맸다.
                        log.error("[openai] 요청 한도에 걸렸습니다. OpenAI 가 약 {}분 뒤에 오라고 해서 "
                                        + "이 단계를 건너뜁니다.{}",
                                waitMs / 60000, limitDiagnosis(errorBody));
                        log.error("[openai] 응답 원문 — {}", abbreviate(errorBody));
                        log.error("[openai] 한도 헤더 — {}", limitHeaders(e.getResponseHeaders()));
                        return fail("AI 요청 한도에 걸려 이 단계를 수행하지 못했습니다.");
                    }

                    if (attempt < MAX_ATTEMPTS) {
                        log.warn("[openai] 요청 한도 초과. {}초 후 재시도 ({}/{}) · 간격을 {}ms 로 늘렸습니다",
                                waitMs / 1000, attempt, MAX_ATTEMPTS, minIntervalMs.get());
                        sleep(waitMs);
                        continue;
                    }

                    log.error("[openai] 요청 한도 초과로 포기했습니다.{} 응답 원문 — {}",
                            limitDiagnosis(errorBody), abbreviate(errorBody));
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

        // 평균 토큰이 처음 추정치와 많이 다르면 간격을 다시 잡는다.
        // 프롬프트가 커서 호출당 토큰이 예상보다 크면 토큰 한도가 먼저 마른다.
        if (total[0] > 0) {
            long avg = (total[1] + total[3]) / total[0];
            long before = avgTokensPerCall.getAndSet(Math.max(500, avg));
            if (knownRpm.get() > 0 && Math.abs(avg - before) > before * 0.3) {
                applyRpm((int) knownRpm.get(), "토큰 재측정");
            }
        }

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
     * 응답 헤더를 보고 보내는 속도를 계정 한도에 맞춘다.
     *
     * 크레딧이 남아 있어도 요청 한도는 별개다.
     * 한도는 계정 등급(usage tier)이 정하고, 등급은 누적 결제액으로 올라간다.
     * 헤더에 찍히는 숫자가 우리 계정의 진짜 상한이다.
     *
     * 예전에는 이 값을 로그로 찍기만 하고 실제 속도에는 반영하지 않았다.
     * "분당 10건입니다" 를 보여주면서 250ms 간격으로 계속 보냈다는 뜻이다.
     * 이제는 읽어서 바로 간격에 반영한다.
     *
     * 남은 횟수도 함께 본다. 429 는 맞고 나서 대응하는 것보다
     * 맞기 전에 멈추는 편이 훨씬 싸다. 한 번 맞으면 수십 분 벌칙이 붙는다.
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
     * 429 응답에서 **어떤 한도**에 걸렸는지 읽어 사람이 읽을 문장으로 만든다.
     *
     * 셋은 대응이 완전히 다르다. 구분하지 않으면 엉뚱한 걸 고치게 된다.
     *
     *   RPM  분당 요청 수. 우리가 빨리 보낸 것 → 간격을 늘리면 해결된다
     *   RPD  하루 요청 수. 오늘 할당량 소진   → 간격을 늘려도 소용없다. 내일이거나 등급 인상
     *   TPM  분당 토큰 수. 프롬프트가 큰 것   → 창 크기를 줄여야 한다
     *
     * 로그에 "요청 한도 초과" 라고만 적으면 셋 다 같은 말로 보인다.
     * 그러면 간격만 계속 만지다가 시간을 버린다.
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
                    + "대본 창 크기(SpeechReviewAnalyzer.WINDOW_SIZE)를 줄여야 합니다.";
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
     * 다음 차례를 받아 그때까지 기다린다.
     *
     * "지금 시각 - 마지막 호출" 을 재는 대신 순번을 미리 잡는 이유는,
     * 스레드 두 개가 같은 값을 읽고 둘 다 조금만 기다린 뒤
     * 동시에 나가는 일을 막기 위해서다. 그게 429 의 흔한 원인이다.
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
