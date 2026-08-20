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

    /**
     * 같은 분석 서버용이지만 **살아 있는지만 묻는** 클라이언트.
     *
     * 위 analysisRestClient 는 읽기 타임아웃이 10분이다. STT·OCR 이 그만큼
     * 걸리니 그건 맞다. 그런데 그 클라이언트로 /health 를 부르면,
     * 서버가 OCR 로 바쁠 때 핑 하나가 **최대 10분** 매달린다.
     *
     * 업로드 직전에 이 핑을 넣었더니 업로드가 통째로 타임아웃됐다.
     * 앞 영상의 OCR 이 도는 중에 다음 영상을 올리면 그렇게 된다.
     * 살아 있는지 묻는 데 10분을 기다릴 이유는 없다.
     *
     * 길이 재기(/probe)도 여기로 보낸다. 업로드 파일은 로컬에 있으므로
     * ffprobe 한 번이면 끝나고, 실패하면 호출한 쪽이 그냥 통과시킨다.
     */
    @Bean
    public RestClient analysisQuickRestClient(AnalysisServerProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(20));

        return RestClient.builder()
                .baseUrl(properties.baseUrl() != null ? properties.baseUrl() : "http://localhost:8000")
                .requestFactory(factory)
                .build();
    }

    /**
     * 구글 뉴스 RSS 용. 인증이 없는 공개 피드라 키가 필요 없다.
     * 기본 User-Agent 로는 응답이 막히는 경우가 있어 브라우저 형태로 보낸다.
     */
    @Bean
    public RestClient googleNewsRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(15));

        return RestClient.builder()
                .baseUrl("https://news.google.com")
                .requestFactory(factory)
                .defaultHeader("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                .defaultHeader("Accept-Language", "ko-KR,ko;q=0.9")
                .build();
    }

    /** 네이버 검색 API 용. 인증 헤더를 기본값으로 붙여둔다. */
    @Bean
    public RestClient naverRestClient(NaverProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(15));

        return RestClient.builder()
                .baseUrl("https://openapi.naver.com")
                .requestFactory(factory)
                .defaultHeader("X-Naver-Client-Id",
                        properties.clientId() == null ? "" : properties.clientId())
                .defaultHeader("X-Naver-Client-Secret",
                        properties.clientSecret() == null ? "" : properties.clientSecret())
                .build();
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
