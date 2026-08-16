package com.example.oops.analyzer;

import com.example.oops.domain.ReviewReference;
import com.example.oops.news.NewsSearchClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 검색한 기사를 프롬프트에 넣고, LLM 이 실제로 근거로 쓴 기사를 참고 자료로 돌려받는 부분.
 *
 * 검색 결과를 전부 붙이면 카드가 링크 더미가 되고,
 * 아무것도 안 붙이면 사용자가 AI 설명을 검증할 수 없다.
 * 그래서 LLM 에게 "몇 번 기사를 봤는지" 를 같이 물어본다.
 */
final class NewsReferenceSupport {

    /** LLM 이 근거를 지목하지 못했을 때 대신 붙일 개수 */
    private static final int FALLBACK_COUNT = 2;

    private NewsReferenceSupport() {}

    /** 프롬프트에 넣을 기사 목록. 번호를 붙여야 LLM 이 어느 기사인지 지목할 수 있다. */
    static String format(List<NewsSearchClient.NewsItem> news) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < news.size(); i++) {
            NewsSearchClient.NewsItem item = news.get(i);
            sb.append("[%d] (%s) %s%n    %s%n".formatted(
                    i,
                    item.pubDate() == null || item.pubDate().isBlank() ? "날짜미상" : item.pubDate(),
                    item.title(),
                    item.description() == null ? "" : item.description()));
        }
        return sb.toString();
    }

    /**
     * LLM 이 지목한 기사를 참고 자료로 바꾼다.
     *
     * 지목하지 못했으면 검색 결과 앞쪽 몇 건을 대신 붙인다.
     * 근거가 약하다는 것과 근거를 안 보여주는 것은 다르다.
     */
    static List<ReviewReference> pick(List<NewsSearchClient.NewsItem> news,
                                      List<Integer> indexes, String relevantContext) {
        List<NewsSearchClient.NewsItem> chosen = new ArrayList<>();

        if (indexes != null) {
            for (Integer index : indexes) {
                if (index != null && index >= 0 && index < news.size()) {
                    NewsSearchClient.NewsItem item = news.get(index);
                    if (!chosen.contains(item)) {
                        chosen.add(item);
                    }
                }
            }
        }
        if (chosen.isEmpty()) {
            chosen.addAll(news.subList(0, Math.min(FALLBACK_COUNT, news.size())));
        }

        return chosen.stream()
                .map(item -> {
                    ReviewReference reference = ReviewReference.of(
                            item.title(),
                            item.publisher(),
                            item.link(),
                            item.pubDate(),
                            item.description());
                    reference.describeRelevance(relevantContext);
                    return reference;
                })
                .toList();
    }
}
