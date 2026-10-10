package com.example.oops.news;

import com.example.oops.config.SearchApiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serper (구글 검색 결과). (2026-10 고도화, 웹 검색 보조)
 *
 *   POST https://google.serper.dev/search  일반 웹 검색 → organic[]
 *   POST https://google.serper.dev/news    뉴스 검색    → news[]
 *
 * 최근 이슈 확인은 날짜가 분명한 뉴스 검색을, 사실 확인은 일반 웹 검색을 쓴다.
 * 결과 10개까지는 1크레딧이다. 그 이상 받으면 2크레딧이라 10개로 묶는다.
 */
@Slf4j
@Component
public class SerperSearch {

    private static final int MAX_RESULTS = 10;

    private final RestClient restClient;
    private final SearchApiProperties properties;

    public SerperSearch(@Qualifier("serperRestClient") RestClient restClient,
                        SearchApiProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.serperOrEmpty().isConfigured();
    }

    public List<NewsSearchClient.NewsItem> search(String query, int max, boolean recent) {
        if (!isEnabled() || query == null || query.isBlank()) return List.of();
        SearchApiProperties.Serper cfg = properties.serperOrEmpty();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("q", query);
        body.put("gl", cfg.glOrDefault());
        body.put("hl", cfg.hlOrDefault());
        body.put("num", Math.max(1, Math.min(MAX_RESULTS, max)));
        if (recent) {
            body.put("tbs", "qdr:m");   // 최근 한 달
        }

        try {
            Response response = restClient.post()
                    .uri(recent ? "/news" : "/search")
                    .body(body)
                    .retrieve()
                    .body(Response.class);
            List<Response.Item> raw = response == null ? null : (recent ? response.news() : response.organic());
            if (raw == null) {
                SearchUsage.serper(true, 0);
                return List.of();
            }

            int limit = properties.maxSnippetCharsOrDefault();
            List<NewsSearchClient.NewsItem> items = raw.stream()
                    .filter(r -> r.link() != null && !r.link().isBlank())
                    .map(r -> new NewsSearchClient.NewsItem(
                            r.title() == null ? "" : r.title().trim(),
                            WebSearchClient.clip(r.snippet(), limit),
                            r.date(),
                            r.link(),
                            r.source()))
                    .toList();
            SearchUsage.serper(true, items.size());
            log.info("[search:serper] {} query='{}' → {}건", recent ? "뉴스" : "웹", query, items.size());
            return items;
        } catch (RestClientException e) {
            SearchUsage.serper(false, 0);
            log.warn("[search:serper] 검색 실패 query={} : {}", query, e.getMessage());
            return List.of();
        }
    }

    record Response(List<Item> organic, List<Item> news) {
        record Item(String title, String link, String snippet, String date, String source) {}
    }
}
