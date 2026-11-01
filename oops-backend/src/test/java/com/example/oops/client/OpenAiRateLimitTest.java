package com.example.oops.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요청 한도 헤더 읽기.
 *
 * 이 파싱이 틀리면 조용히 실패한다.
 * 값을 못 읽으면 그냥 0 이 되고, 0 이면 "쉬지 않아도 된다" 로 읽혀서
 * 그대로 한도를 넘긴다. 그러면 OpenAI 가 수십 분 벌칙을 주고
 * 그 영상은 분석 결과가 통째로 빈다.
 *
 * 실제로 겪은 일이라 형식별로 다 걸어둔다.
 */
class OpenAiRateLimitTest {

    @Test
    @DisplayName("초 단위")
    void seconds() {
        assertThat(OpenAiClient.parseDurationMs("2s")).isEqualTo(2000);
        assertThat(OpenAiClient.parseDurationMs("2.5s")).isEqualTo(2500);
    }

    @Test
    @DisplayName("밀리초 — s 로 잘못 읽으면 1000배가 된다")
    void milliseconds() {
        // "6ms" 를 "6s" 로 읽으면 6초를 쉰다. 호출마다 그러면 분석이 안 끝난다.
        assertThat(OpenAiClient.parseDurationMs("6ms")).isEqualTo(6);
        assertThat(OpenAiClient.parseDurationMs("500ms")).isEqualTo(500);
    }

    @Test
    @DisplayName("분과 초가 붙어 오는 형식")
    void combined() {
        assertThat(OpenAiClient.parseDurationMs("1m30s")).isEqualTo(90_000);
        assertThat(OpenAiClient.parseDurationMs("28m0s")).isEqualTo(1_680_000);
    }

    @Test
    @DisplayName("분 단위")
    void minutes() {
        assertThat(OpenAiClient.parseDurationMs("6m")).isEqualTo(360_000);
    }

    @Test
    @DisplayName("못 읽는 값은 0 — 쉬지 않고 그냥 진행한다")
    void unparseable() {
        assertThat(OpenAiClient.parseDurationMs(null)).isZero();
        assertThat(OpenAiClient.parseDurationMs("")).isZero();
        assertThat(OpenAiClient.parseDurationMs("unknown")).isZero();
    }

    @Test
    @DisplayName("남은 횟수 헤더")
    void remaining() {
        assertThat(OpenAiClient.parseLong("10")).isEqualTo(10L);
        assertThat(OpenAiClient.parseLong(" 0 ")).isZero();
        assertThat(OpenAiClient.parseLong(null)).isNull();
        assertThat(OpenAiClient.parseLong("N/A")).isNull();
    }

    @Test
    @DisplayName("어떤 한도인지 구분한다 — 대응이 완전히 다르다")
    void tellsWhichLimit() {
        String rpd = """
                {"error":{"message":"Rate limit reached for gpt-4o-mini in organization org-x \
                on requests per day (RPD): Limit 200, Used 200. Please try again in 28m0s.",\
                "type":"requests","code":"rate_limit_exceeded"}}""";
        String rpm = """
                {"error":{"message":"Rate limit reached for gpt-4o-mini on requests per min (RPM): \
                Limit 50, Used 50.","type":"requests","code":"rate_limit_exceeded"}}""";
        String tpm = """
                {"error":{"message":"Rate limit reached on tokens per min (TPM): Limit 60000.",\
                "type":"tokens","code":"rate_limit_exceeded"}}""";

        // 하루 한도는 간격을 늘려도 안 풀린다. 그 사실이 문구에 있어야 한다.
        assertThat(OpenAiClient.limitDiagnosis(rpd)).contains("RPD", "해결되지 않습니다");
        // 분당 한도는 간격 문제다
        assertThat(OpenAiClient.limitDiagnosis(rpm)).contains("RPM", "requests-per-minute");
        // 토큰 한도는 횟수가 아니라 프롬프트 크기 문제다
        assertThat(OpenAiClient.limitDiagnosis(tpm)).contains("TPM", "크기");
    }

    @Test
    @DisplayName("본문이 없으면 모른다고 말한다")
    void admitsWhenUnknown() {
        // 아는 척하면 엉뚱한 곳을 고치게 된다
        assertThat(OpenAiClient.limitDiagnosis("")).contains("알 수 없");
        assertThat(OpenAiClient.limitDiagnosis(null)).contains("알 수 없");
    }

    @Test
    @DisplayName("분당 한도를 간격으로 바꾸면 여유분이 남아야 한다")
    void intervalKeepsHeadroom() {
        // 분당 10건이면 6000ms 가 딱 맞는 간격이다.
        // 딱 맞게 보내면 경계에서 걸리므로 그보다 넉넉해야 한다.
        long interval = (long) Math.ceil(60_000.0 / (10 * 0.9));

        assertThat(interval).isGreaterThan(6000);
        assertThat(60_000 / interval).isLessThan(10);
    }
}
