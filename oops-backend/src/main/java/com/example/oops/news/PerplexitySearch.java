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
 * Perplexity Search API. (2026-10 고도화, 웹 검색 메인)
 *
 * POST https://api.perplexity.ai/search
 * 결과마다 제목·주소·본문 일부(snippet)·날짜를 준다. 요청 한 번에 과금되며 토큰 요금은 없다.
 */
@Slf4j
@Component
public class PerplexitySearch {

    private final RestClient restClient;
    private final SearchApiProperties properties;

    public PerplexitySearch(@Qualifier("perplexityRestClient") RestClient restClient,
                            SearchApiProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.perplexityOrEmpty().isConfigured();
    }

    /**
     * @param recent true 면 최근 한 달 자료만 (최근 이슈 확인용)
     */
    public List<NewsSearchClient.NewsItem> search(String query, int max, boolean recent) {
        if (!isEnabled() || query == null || query.isBlank()) return List.of();
        SearchApiProperties.Perplexity cfg = properties.perplexityOrEmpty();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("max_results", Math.max(1, Math.min(20, max)));
        body.put("max_tokens_per_page", cfg.maxTokensPerPageOrDefault());
        body.put("search_type", recent ? cfg.recentSearchTypeOrDefault() : cfg.searchTypeOrDefault());
        body.put("country", cfg.countryOrDefault());
        if (cfg.languages() != null && !cfg.languages().isEmpty()) {
            body.put("search_language_filter", cfg.languages());
        }
        if (recent) {
            body.put("search_recency_filter", "month");
        }

        try {
            Response response = restClient.post()
                    .uri("/search")
                    .body(body)
                    .retrieve()
                    .body(Response.class);
            if (response == null || response.results() == null) {
                SearchUsage.perplexity(true, 0);
                return List.of();
            }

            int limit = properties.maxSnippetCharsOrDefault();
            List<NewsSearchClient.NewsItem> items = response.results().stream()
                    .filter(r -> r.url() != null && !r.url().isBlank())
                    .map(r -> new NewsSearchClient.NewsItem(
                            r.title() == null ? "" : r.title().trim(),
                            WebSearchClient.clip(r.snippet(), limit),
                            r.date() != null ? r.date() : r.last_updated(),
                            r.url(),
                            null))
                    .toList();
            SearchUsage.perplexity(true, items.size());
            log.info("[search:perplexity] {} query='{}' → {}건", recent ? "최근" : "전체", query, items.size());
            return items;
        } catch (RestClientException e) {
            SearchUsage.perplexity(false, 0);
            log.warn("[search:perplexity] 검색 실패 query={} : {}", query, e.getMessage());
            return List.of();
        }
    }

    record Response(List<Result> results) {
        record Result(String title, String url, String snippet, String date, String last_updated) {}
    }
}
