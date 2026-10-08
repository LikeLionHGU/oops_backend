package com.example.oops.client;

import com.example.oops.config.OpenAiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OpenAiClientTest {
    private OpenAiProperties properties(String model, String effort) {
        return new OpenAiProperties("offline-test-key", null, null, "https://example.invalid", model,
                effort, Duration.ofSeconds(2), null);
    }

    @Test
    void lunaDefaultKeepsNonReasoningBaselineAndExistingJsonContract() {
        var properties = properties(null, null);
        var client = new OpenAiClient(RestClient.create(), properties);
        assertThat(properties.modelOrDefault()).isEqualTo("gpt-6-luna");
        assertThat(client.requestBody("JSON system", "input"))
                .containsEntry("model", "gpt-6-luna").containsEntry("reasoning_effort", "none")
                .containsEntry("temperature", 0.1).containsKeys("response_format", "messages");
    }

    @Test
    void reasoningLunaOmitsSamplingParametersAndLegacyModelCanStillBeExplicitlySelected() {
        for (String effort : java.util.List.of("low", "medium", "high", "xhigh", "max")) {
            var client = new OpenAiClient(RestClient.create(), properties("gpt-6-luna", effort));
            assertThat(client.requestBody("JSON", "input")).containsEntry("reasoning_effort", effort)
                    .doesNotContainKeys("temperature", "top_p", "logprobs");
        }
        var legacy = new OpenAiClient(RestClient.create(), properties("gpt-4o-mini", null));
        assertThat(legacy.requestBody("JSON", "input")).containsEntry("model", "gpt-4o-mini")
                .containsEntry("temperature", 0.1).doesNotContainKey("reasoning_effort");
        assertThatThrownBy(() -> new OpenAiClient(RestClient.create(), properties("gpt-6-luna", "minimal"))
                .requestBody("JSON", "input")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mockHttpVerifiesActualLunaPayloadDeserializationAndUsageWithoutPaidCalls() {
        var builder = RestClient.builder().baseUrl("https://example.invalid");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://example.invalid/chat/completions"))
                .andExpect(jsonPath("$.model").value("gpt-6-luna"))
                .andExpect(jsonPath("$.reasoning_effort").value("none"))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andRespond(withSuccess(mockResponse(), MediaType.APPLICATION_JSON));
        var client = new OpenAiClient(builder.build(), properties(null, null));
        client.beginVideo(1L);
        try {
            assertThat(client.completeAsJson("Return JSON", "input", Answer.class)).contains(new Answer(true));
            assertThat(client.videoUsage().calls()).isOne();
            assertThat(client.videoUsage().model()).isEqualTo("gpt-6-luna");
            assertThat(client.videoUsage().cachedTokens()).isEqualTo(40);
            assertThat(client.failureCount()).isZero();
        } finally { client.endVideo(); }
        server.verify();
    }

    @Test
    void refusalTruncatedAndEmptyResponsesAreRecordedAsFailuresNotSuccessfulEmptyResults() {
        for (String json : java.util.List.of(
                """
                {"choices":[{"finish_reason":"stop","message":{"content":null,"refusal":"refused"}}]}
                """,
                """
                {"choices":[{"finish_reason":"length","message":{"content":"{}"}}]}
                """,
                """
                {"choices":[{"finish_reason":"content_filter","message":{"content":"{}"}}]}
                """, "{\"choices\":[]}", "{\"choices\":null}")) {
            var builder = RestClient.builder().baseUrl("https://example.invalid");
            var server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://example.invalid/chat/completions"))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
            var client = new OpenAiClient(builder.build(), properties(null, null));
            client.beginVideo(1L);
            try {
                assertThat(client.completeAsJson("Return JSON", "input", Answer.class)).isEmpty();
                assertThat(client.failureCount()).isOne();
                assertThat(client.failureReason()).isPresent();
            } finally { client.endVideo(); }
            server.verify();
        }
    }

    @Test
    void lunaDefaultPricingAccountsForCachedAndFreshTokensSeparately() {
        var pricing = properties(null, null).pricing();
        var usage = new OpenAiClient.TokenUsage(1, 2_000_000, 1_000_000, 1_000_000, "gpt-6-luna", pricing);
        assertThat(usage.costUsd()).isCloseTo(0.61, within(0.000001));
        assertThat(pricing.sttUsdPerMinute()).isEqualTo(0.006); // Whisper remains active.
    }

    @Test
    @SuppressWarnings("unchecked")
    void systemAlwaysHasExplicitJsonInstructionEvenWhenBothOriginalMessagesLackJson() {
        var client = new OpenAiClient(RestClient.create(), properties(null, null));
        var body = client.requestBody("대화 묶음 하나만 판정한다.", "일반 영상 원문");
        var messages = (java.util.List<java.util.Map<String, String>>) body.get("messages");
        assertThat(messages.get(0).get("content")).startsWith("대화 묶음 하나만 판정한다.")
                .endsWith(OpenAiClient.JSON_OUTPUT_INSTRUCTION).contains("JSON");
        assertThat(messages.get(1).get("content")).isEqualTo("일반 영상 원문");
        assertThat(body.get("response_format")).isEqualTo(java.util.Map.of("type", "json_object"));
    }

    @Test
    void actualMockHttpPayloadHasJsonInstructionWithoutDependingOnAnalyzerWording() {
        var builder = RestClient.builder().baseUrl("https://example.invalid");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://example.invalid/chat/completions"))
                .andExpect(jsonPath("$.messages[0].content").value("대화만 판정한다.\n" + OpenAiClient.JSON_OUTPUT_INSTRUCTION))
                .andExpect(jsonPath("$.messages[1].content").value("원문"))
                .andRespond(withSuccess(mockResponse(), MediaType.APPLICATION_JSON));
        var client = new OpenAiClient(builder.build(), properties(null, null));
        client.beginVideo(1L);
        try { assertThat(client.completeAsJson("대화만 판정한다.", "원문", Answer.class)).contains(new Answer(true)); }
        finally { client.endVideo(); }
        server.verify();
    }

    record Answer(boolean ok) {}

    private String mockResponse() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        return mapper.writeValueAsString(java.util.Map.of("model", "gpt-6-luna",
                "choices", java.util.List.of(java.util.Map.of("finish_reason", "stop",
                        "message", java.util.Map.of("content", mapper.writeValueAsString(new Answer(true))))),
                "usage", java.util.Map.of("prompt_tokens", 100, "completion_tokens", 20, "total_tokens", 120,
                        "prompt_tokens_details", java.util.Map.of("cached_tokens", 40))));
    }
}
