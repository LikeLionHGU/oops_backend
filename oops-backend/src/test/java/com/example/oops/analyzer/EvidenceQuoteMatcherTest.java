package com.example.oops.analyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceQuoteMatcherTest {

    @Test
    @DisplayName("인용한 문구가 실제 원문에 있으면 연결을 인정한다")
    void acceptsSourceExcerpt() {
        assertThat(EvidenceQuoteMatcher.matches("돈이 아깝다", "그 돈이 아깝다고 느꼈어요")).isTrue();
    }

    @Test
    @DisplayName("문장부호와 공백 차이는 허용한다")
    void normalizesSpacingAndPunctuation() {
        assertThat(EvidenceQuoteMatcher.matches("돈이 아깝다.", "그 돈 이, 아깝다고 느꼈어요")).isTrue();
    }

    @Test
    @DisplayName("원문에 없는 인용은 거부한다")
    void rejectsExcerptAbsentFromSource() {
        assertThat(EvidenceQuoteMatcher.matches("사기였다", "그 음식은 제 입에 맞지 않았어요")).isFalse();
    }

    @Test
    @DisplayName("빈 인용은 거부한다")
    void rejectsEmptyExcerpt() {
        assertThat(EvidenceQuoteMatcher.matches(" ", "그 돈이 아깝다고 느꼈어요")).isFalse();
    }
}
