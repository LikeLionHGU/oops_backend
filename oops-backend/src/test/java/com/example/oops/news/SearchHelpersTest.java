package com.example.oops.news;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchHelpersTest {

    private static NewsSearchClient.NewsItem item(String link) {
        return new NewsSearchClient.NewsItem("제목", "본문", null, link, null);
    }

    @Test
    @DisplayName("기사 번호가 ? 뒤에 있는 언론사 기사는 서로 다른 자료로 남는다")
    void dedupeKeepsQueryIds() {
        assertThat(WebSearchClient.dedupeKey(item("https://www.news.kr/articleView.html?idxno=1")))
                .isNotEqualTo(WebSearchClient.dedupeKey(item("https://www.news.kr/articleView.html?idxno=2")));
        assertThat(WebSearchClient.dedupeKey(item("https://a.kr/x?id=1&utm_source=foo#top")))
                .isEqualTo(WebSearchClient.dedupeKey(item("https://a.kr/x?id=1")));
    }

    @Test
    @DisplayName("도메인은 점 경계까지 맞춰 본다")
    void domainBoundary() {
        assertThat(SourceClassifier.matchesDomain("netflix.com", "x.com")).isFalse();
        assertThat(SourceClassifier.matchesDomain("x.com", "x.com")).isTrue();
        assertThat(SourceClassifier.matchesDomain("mobile.x.com", "x.com")).isTrue();
        assertThat(SourceClassifier.matchesDomain("www.korea.go.kr", ".go.kr")).isTrue();
    }
}
