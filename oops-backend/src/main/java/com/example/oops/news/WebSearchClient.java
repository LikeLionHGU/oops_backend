package com.example.oops.news;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 웹 검색: Perplexity 메인, Serper 보조. (2026-10 고도화)
 *
 * 둘 중 하나라도 키가 있으면 이 클라이언트가 가장 먼저 선택된다 (@Order(0)).
 * 분석기는 지금처럼 NewsSearchClient 하나만 보고, 어디서 찾았는지는 몰라도 된다.
 *
 * 메인이 결과를 MIN_RESULTS 개 미만으로 주면 보조로 이어서 찾고, 같은 주소는 한 번만 넣는다.
 * 둘 다 비면 네이버 뉴스 → 구글 뉴스 RSS 로 내려간다. 검색이 안 됐다는 이유로
 * 사실 확인이 통째로 빠지는 것보다 뉴스라도 대조하는 게 낫다.
 */
@Slf4j
@Order(0)
@Component
public class WebSearchClient implements NewsSearchClient {

    /** 메인 결과가 이보다 적으면 보조로 더 찾는다 */
    static final int MIN_RESULTS = 3;

    private final PerplexitySearch perplexity;
    private final SerperSearch serper;
    private final NaverNewsSearchClient naver;
    private final GoogleNewsRssSearchClient googleRss;

    public WebSearchClient(PerplexitySearch perplexity, SerperSearch serper,
                           NaverNewsSearchClient naver, GoogleNewsRssSearchClient googleRss) {
        this.perplexity = perplexity;
        this.serper = serper;
        this.naver = naver;
        this.googleRss = googleRss;
    }

    @Override
    public boolean isEnabled() {
        return perplexity.isEnabled() || serper.isEnabled();
    }

    @Override
    public String providerName() {
        if (perplexity.isEnabled() && serper.isEnabled()) return "Perplexity+Serper";
        return perplexity.isEnabled() ? "Perplexity" : "Serper";
    }

    /** 최근 이슈 확인용. 최근 한 달. */
    @Override
    public List<NewsItem> searchRecent(String query, int display) {
        return search(query, display, true);
    }

    /** 사실 확인용. 기간 제한 없이. */
    @Override
    public List<NewsItem> searchArchive(String query, int display) {
        return search(query, display, false);
    }

    private List<NewsItem> search(String query, int display, boolean recent) {
        Map<String, NewsItem> merged = new LinkedHashMap<>();
        addAll(merged, perplexity.search(query, display, recent));
        if (merged.size() < Math.min(MIN_RESULTS, display)) {
            addAll(merged, serper.search(query, display, recent));
        }
        if (merged.isEmpty()) {
            NewsSearchClient fallback = naver.isEnabled() ? naver : googleRss;
            SearchUsage.newsFallback();
            log.info("[search] 웹 검색 결과가 없어 {} 로 찾습니다. query='{}'", fallback.providerName(), query);
            addAll(merged, recent ? fallback.searchRecent(query, display) : fallback.searchArchive(query, display));
        }
        List<NewsItem> result = new ArrayList<>(merged.values());
        return result.size() > display ? result.subList(0, display) : result;
    }

    private static void addAll(Map<String, NewsItem> into, List<NewsItem> items) {
        for (NewsItem item : items) {
            into.putIfAbsent(dedupeKey(item), item);
        }
    }

    /**
     * 같은 자료인지 가르는 열쇠.
     * 주소 뒤 #조각과 추적용 값(utm_*, fbclid 등)만 지운다. ?뒤 전체를 지우면
     * "articleView.html?idxno=123" 처럼 기사 번호가 ? 뒤에 있는 언론사 기사가 전부 하나로 합쳐진다.
     */
    static String dedupeKey(NewsItem item) {
        if (item.link() == null || item.link().isBlank()) return "title:" + item.title();
        String url = item.link().trim().replaceAll("#.*$", "");
        int q = url.indexOf('?');
        if (q < 0) return url.replaceAll("/$", "");
        String base = url.substring(0, q).replaceAll("/$", "");
        String kept = java.util.Arrays.stream(url.substring(q + 1).split("&"))
                .filter(p -> !p.isBlank())
                .filter(p -> !p.matches("(?i)(utm_[^=]*|fbclid|gclid|ref|ref_src|from)(=.*)?"))
                .sorted()
                .collect(java.util.stream.Collectors.joining("&"));
        return kept.isEmpty() ? base : base + "?" + kept;
    }

    /** 자료 본문을 AI 에게 넘길 길이로 자른다. 줄바꿈과 겹친 공백은 한 칸으로. */
    static String clip(String text, int limit) {
        if (text == null) return "";
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }
}
