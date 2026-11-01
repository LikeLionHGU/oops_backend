package com.example.oops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    /**
     * Python 분석 서버용.
     *
     * HTTP/1.1 로 고정하는 이유:
     * 자바의 HttpClient 는 기본이 HTTP/2 라서, 평문 HTTP 에 붙을 때
     * "Connection: Upgrade" 헤더로 h2c 업그레이드를 먼저 시도한다.
     * uvicorn(h11)은 이걸 지원하지 않아 요청 본문이 깨지고 422 가 돌아온다.
     * (분석 서버 로그에 "Unsupported upgrade request" 가 찍힌다.)
     *
     * STT/OCR 은 몇 분씩 걸리므로 읽기 타임아웃도 길게 준다.
     */
    @Bean
    public RestClient analysisRestClient(AnalysisServerProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.timeoutOrDefault());

        return RestClient.builder()
                .baseUrl(properties.baseUrl() != null ? properties.baseUrl() : "http://localhost:8000")
                .requestFactory(factory)
                .build();
    }

    /** OCR only: local zero means no total read deadline; connection still bounded. */
    @Bean
    public RestClient analysisOcrRestClient(AnalysisServerProperties properties) {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
        var factory = new JdkClientHttpRequestFactory(client);
        if (!properties.ocrTimeoutOrDefault().isZero()) {
            factory.setReadTimeout(properties.ocrTimeoutOrDefault());
        }
        return RestClient.builder()
                .baseUrl(properties.baseUrl() != null ? properties.baseUrl() : "http://localhost:8000")
                .requestFactory(factory).build();
    }

    /** 헬스 확인과 업로드 전 Worker 확인은 분석 호출과 별도의 짧은 제한을 쓴다. */
    @Bean
    public RestClient analysisHealthRestClient(AnalysisServerProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(2));

        return RestClient.builder()
                .baseUrl(properties.baseUrl() != null ? properties.baseUrl() : "http://localhost:8000")
                .requestFactory(factory)
                .build();
    }

    /** Perplexity Search API 용. (redesign-v3 사실대조 1차 검색) */
    @Bean
    public RestClient perplexityRestClient(SearchApiProperties properties) {
        String key = properties.perplexityOrEmpty().apiKey();
        return RestClient.builder()
                .baseUrl("https://api.perplexity.ai")
                .requestFactory(searchRequestFactory())
                .defaultHeader("Authorization", "Bearer " + (key == null ? "" : key))
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /** Serper(구글 검색) 용. (redesign-v3 사실대조 보조 검색) */
    @Bean
    public RestClient serperRestClient(SearchApiProperties properties) {
        String key = properties.serperOrEmpty().apiKey();
        return RestClient.builder()
                .baseUrl("https://google.serper.dev")
                .requestFactory(searchRequestFactory())
                .defaultHeader("X-API-KEY", key == null ? "" : key)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    private JdkClientHttpRequestFactory searchRequestFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(30));
        return factory;
    }

    /**
     * OpenAI 용. HTTPS 라 ALPN 으로 프로토콜을 협상하므로 h2c 업그레이드 문제가 없다.
     * 그래도 동작을 예측 가능하게 하려고 명시적으로 HTTP/1.1 을 쓴다.
     */
    @Bean
    public RestClient openAiRestClient(OpenAiProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.timeoutOrDefault());

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.baseUrlOrDefault())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + (properties.apiKey() == null ? "" : properties.apiKey()));

        // 지원 크레딧이 특정 조직에 붙어 있는 경우, 어느 조직으로 과금할지 명시한다.
        if (properties.hasOrganization()) {
            builder.defaultHeader("OpenAI-Organization", properties.organization());
        }
        if (properties.hasProject()) {
            builder.defaultHeader("OpenAI-Project", properties.project());
        }

        return builder.build();
    }
}
