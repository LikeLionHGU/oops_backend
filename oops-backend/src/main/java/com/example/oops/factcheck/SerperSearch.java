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
 * Serper(구글 검색) — 사실대조 보조 검색. (upgrade-v2 연결부를 redesign-v3 계약으로 옮김)
 *
 * POST https://google.serper.dev/search → organic[]. 결과 10개까지 1크레딧.
 * 주장당 최대 1회만 부른다(FactCheckEngine 이 지킨다).
 */
@Slf4j
@Component
public class SerperSearch implements Search.Provider {

    private static final int MAX_RESULTS = 10;

    private final RestClient restClient;
    private final SearchApiProperties properties;

    public SerperSearch(@Qualifier("serperRestClient") RestClient restClient, SearchApiProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "serper";
    }

    @Override
    public boolean isEnabled() {
        return properties.serperOrEmpty().isConfigured();
    }

    @Override
    public Search.Outcome search(String query, int max) {
        if (!isEnabled()) return Search.Outcome.disabled(name(), query);
        var cfg = properties.serperOrEmpty();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("q", query);
        body.put("gl", cfg.glOrDefault());
        body.put("hl", cfg.hlOrDefault());
        body.put("num", Math.max(1, Math.min(MAX_RESULTS, max)));
        try {
            Response response = restClient.post().uri("/search").body(body).retrieve().body(Response.class);
            if (response == null || response.organic() == null) return Search.Outcome.of(name(), query, List.of());
            int limit = properties.maxSnippetCharsOrDefault();
            List<Search.Item> items = response.organic().stream()
                    .filter(r -> r.link() != null && !r.link().isBlank())
                    .map(r -> new Search.Item(r.title() == null ? "" : r.title().trim(), Search.clip(r.snippet(), limit),
                            r.date(), r.link(), r.source()))
                    .toList();
            log.info("[search:serper] query='{}' → {}건", query, items.size());
            return Search.Outcome.of(name(), query, items);
        } catch (RestClientException e) {
            log.warn("[search:serper] 검색 실패 query='{}' : {}", query, e.getMessage());
            return Search.Outcome.failed(name(), query, "Serper 검색 실패");
        }
    }

    record Response(List<Item> organic) {
        record Item(String title, String link, String snippet, String date, String source) {}
    }
}
