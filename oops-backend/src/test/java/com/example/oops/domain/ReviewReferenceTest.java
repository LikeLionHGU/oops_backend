package com.example.oops.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 참고 자료 붙이기.
 *
 * 카드 하나에 기사를 다 쏟아놓으면 아무도 안 본다.
 * 같은 기사가 두 번 붙어도 마찬가지다.
 */
class ReviewReferenceTest {

    private RiskFinding emptyFinding() {
        return RiskFinding.builder()
                .eventType(TimelineEventType.SPEECH)
                .category(RiskCategory.FACT_ERROR)
                .source(EvidenceSource.SUBTITLE)
                .score(0.7)
                .startMs(0)
                .endMs(1000)
                .text("2019년에 설립됐습니다")
                .build();
    }

    private ReviewReference ref(String url) {
        return ReviewReference.of("기사 제목", "한국일보", url, "2026-08-01", "발췌 내용");
    }

    @Test
    @DisplayName("같은 기사는 한 번만 붙는다")
    void dedupesByUrl() {
        RiskFinding finding = emptyFinding();
        finding.addReference(ref("https://example.com/a"));
        finding.addReference(ref("https://example.com/a"));

        assertThat(finding.getReferences()).hasSize(1);
    }

    @Test
    @DisplayName("상한을 넘으면 더 붙이지 않는다")
    void capsCount() {
        RiskFinding finding = emptyFinding();
        for (int i = 0; i < 10; i++) {
            finding.addReference(ref("https://example.com/" + i));
        }

        assertThat(finding.getReferences()).hasSizeLessThanOrEqualTo(4);
    }

    @Test
    @DisplayName("매체명이 없으면 주소에서 뽑아 쓴다")
    void fallsBackToHost() {
        // 네이버 검색 결과에는 매체명이 없다. 빈칸으로 두면 어디 기사인지 알 수 없다.
        ReviewReference r = ReviewReference.of(
                "제목", null, "https://www.hankyung.com/article/123", null, null);

        assertThat(r.getPublisher()).isEqualTo("hankyung.com");
    }

    @Test
    @DisplayName("제목도 주소도 없으면 붙이지 않는다")
    void rejectsEmpty() {
        RiskFinding finding = emptyFinding();
        finding.addReference(ReviewReference.of(null, null, null, null, null));

        assertThat(finding.getReferences()).isEmpty();
    }

    @Test
    @DisplayName("긴 값은 잘라서 저장한다")
    void trimsLongValues() {
        // DB 컬럼 길이를 넘으면 저장 자체가 터진다.
        // 기사 본문이 통째로 들어오는 경우가 있다.
        ReviewReference r = ReviewReference.of(
                "가".repeat(900), "매체", "https://example.com", null, "나".repeat(900));

        assertThat(r.getTitle()).hasSize(500);
        assertThat(r.getSnippet()).hasSize(500);
    }
}
