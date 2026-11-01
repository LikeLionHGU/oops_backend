package com.example.oops.factcheck;

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
 * Perplexity Search API — 사실대조 1차 검색. (upgrade-v2 연결부를 redesign-v3 계약으로 옮김)
 *
 * POST https://api.perplexity.ai/search
 * 결과마다 제목·주소·본문 일부(snippet)·날짜를 준다. 요청 단위 과금.
 */
@Slf4j
@Component
public class PerplexitySearch implements Search.Provider {

    private final RestClient restClient;
    private final SearchApiProperties properties;

    public PerplexitySearch(@Qualifier("perplexityRestClient") RestClient restClient, SearchApiProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "perplexity";
    }

    @Override
    public boolean isEnabled() {
        return properties.perplexityOrEmpty().isConfigured();
    }

    @Override
    public Search.Outcome search(String query, int max) {
        if (!isEnabled()) return Search.Outcome.disabled(name(), query);
        var cfg = properties.perplexityOrEmpty();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("max_results", Math.max(1, Math.min(20, max)));
        body.put("max_tokens_per_page", cfg.maxTokensPerPageOrDefault());
        body.put("search_type", cfg.searchTypeOrDefault());
        body.put("country", cfg.countryOrDefault());
        if (cfg.languages() != null && !cfg.languages().isEmpty()) body.put("search_language_filter", cfg.languages());
        try {
            Response response = restClient.post().uri("/search").body(body).retrieve().body(Response.class);
            if (response == null || response.results() == null) return Search.Outcome.of(name(), query, List.of());
            int limit = properties.maxSnippetCharsOrDefault();
            List<Search.Item> items = response.results().stream()
                    .filter(r -> r.url() != null && !r.url().isBlank())
                    .map(r -> new Search.Item(r.title() == null ? "" : r.title().trim(), Search.clip(r.snippet(), limit),
                            r.date() != null ? r.date() : r.last_updated(), r.url(), null))
                    .toList();
            log.info("[search:perplexity] query='{}' → {}건", query, items.size());
            return Search.Outcome.of(name(), query, items);
        } catch (RestClientException e) {
            log.warn("[search:perplexity] 검색 실패 query='{}' : {}", query, e.getMessage());
            return Search.Outcome.failed(name(), query, "Perplexity 검색 실패");
        }
    }

    record Response(List<Result> results) {
        record Result(String title, String url, String snippet, String date, String last_updated) {}
    }
}
