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

    /**
     * 최근 것만. "지금 이 주제가 민감한가" 를 볼 때 쓴다.
     * 오래된 기사는 현재 분위기 판단에 방해가 되므로 일부러 자른다.
     */
    List<NewsItem> searchRecent(String query, int display);

    /**
     * 기간 제한 없이. 사실 확인에 쓴다.
     *
     * "그 회사는 2019년에 설립됐다" 를 확인하려면 최근 30일 기사로는 안 된다.
     * 예전에는 이것도 searchRecent 를 썼는데, 오래된 사실을 물으면
     * 관련 기사가 아예 안 나와서 조용히 검증을 건너뛰고 있었다.
     */
    default List<NewsItem> searchArchive(String query, int display) {
        return searchRecent(query, display);
    }

    /**
     * 기사 1건.
     *
     * publisher 는 없을 수 있다. 구글 RSS 는 &lt;source&gt; 로 매체명을 주지만
     * 네이버는 주지 않는다. 비어 있으면 링크 주소에서 뽑아 쓴다.
     */
    record NewsItem(String title, String description, String pubDate, String link, String publisher) {

        public NewsItem(String title, String description, String pubDate, String link) {
            this(title, description, pubDate, link, null);
        }
    }
}
