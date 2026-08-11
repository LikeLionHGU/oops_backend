package com.example.videoguard.client;

import com.example.videoguard.config.OpenAiProperties;
import tools.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OpenAI Chat Completions 호출.
 * JSON 강제 모드로 받아서 곧바로 DTO 로 역직렬화한다.
 * 키가 없거나 호출이 실패하면 empty 를 돌려주고, 호출부는 룰 기반 결과만으로 진행한다.
 */
@Slf4j
@Component
public class OpenAiClient {

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

        try {
            ChatCompletionResponse response = restClient.post()
                    .uri("/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(ChatCompletionResponse.class);

            String content = Optional.ofNullable(response)
                    .map(ChatCompletionResponse::choices)
                    .filter(choices -> !choices.isEmpty())
                    .map(choices -> choices.get(0).message().content())
                    .orElse(null);

            if (content == null || content.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(jsonMapper.readValue(content, type));

        } catch (RestClientException e) {
            log.warn("[openai] 호출 실패: {}", e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[openai] 응답 파싱 실패: {}", e.getMessage());
            return Optional.empty();
        }
    }

    record ChatCompletionResponse(List<Choice> choices) {
        record Choice(Message message) {}
        record Message(String content) {}
    }
}
