package com.example.oops.news;

import com.example.oops.config.NaverProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * 네이버 뉴스 검색.
 * 키가 설정돼 있을 때만 동작하며, 설정돼 있으면 구글 RSS 보다 먼저 선택된다.
 * 국내 매체 커버리지와 스니펫 길이가 더 낫기 때문이다.
 */
@Slf4j
@Order(1)
@Component
public class NaverNewsSearchClient implements NewsSearchClient {

    private final RestClient restClient;
    private final NaverProperties properties;

    public NaverNewsSearchClient(@Qualifier("naverRestClient") RestClient restClient,
                                 NaverProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public boolean isEnabled() {
        return properties.isConfigured();
    }

    @Override
    public String providerName() {
        return "네이버뉴스";
    }

    @Override
    public List<NewsItem> searchRecent(String query, int display) {
        if (!isEnabled()) {
            return List.of();
        }
        try {
            SearchResponse response = restClient.get()
                    .uri(builder -> builder.path("/v1/search/news.json")
                            .queryParam("query", query)
                            .queryParam("display", display)
                            .queryParam("sort", "date")
                            .build())
                    .retrieve()
                    .body(SearchResponse.class);

            if (response == null || response.items() == null) {
                return List.of();
            }
            return response.items().stream()
                    .map(item -> new NewsItem(
                            stripHtml(item.title()),
                            stripHtml(item.description()),
                            item.pubDate(),
                            item.link()))
                    .toList();

        } catch (RestClientException e) {
            log.warn("[news:naver] 검색 실패 query={} : {}", query, e.getMessage());
            return List.of();
        }
    }

    /** 네이버 응답에는 <b> 강조 태그와 HTML 엔티티가 섞여 있다. */
    private String stripHtml(String text) {
        if (text == null) return "";
        return text.replaceAll("<[^>]*>", "")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
                .trim();
    }

    record SearchResponse(List<Item> items) {
        record Item(String title, String description, String pubDate, String link) {}
    }
}
