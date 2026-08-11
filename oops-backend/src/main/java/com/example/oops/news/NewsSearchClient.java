package com.example.oops.news;

import java.util.List;

/**
 * 최근 뉴스를 가져오는 역할.
 *
 * LLM 은 학습 시점 이후의 뉴스를 모른다.
 * "지금 이 주제가 민감한가" 를 판단하려면 최신 기사를 직접 넣어줘야 해서 만들었다.
 *
 * 구현체가 여러 개면 @Order 가 앞선 것 중 사용 가능한 첫 번째를 쓴다.
 *   - NaverNewsSearchClient   : 키가 있으면 사용. 한국 언론사 커버리지가 넓다.
 *   - GoogleNewsRssSearchClient : 키 불필요. 기본 폴백.
 */
public interface NewsSearchClient {

    /** 지금 이 구현체를 쓸 수 있는 상태인지 (키 설정 등) */
    boolean isEnabled();

    /** 로그에 찍을 이름 */
    String providerName();

    /** 최신순으로 뉴스를 가져온다. 실패하면 빈 리스트. */
    List<NewsItem> searchRecent(String query, int display);

    record NewsItem(String title, String description, String pubDate, String link) {}
}
