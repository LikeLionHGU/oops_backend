package com.example.oops.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 내부 카테고리 22개를 사용자에게 보여줄 2가지로 줄이는 규칙. 명세 v2.1 §10.
 *
 * 카테고리를 새로 추가하고 여기 안 넣으면 전부 SPEECH_REVIEW 로 떨어진다.
 * 그걸 잡아내는 것이 이 테스트의 목적이다.
 */
class CandidateTypeTest {

    @Test
    @DisplayName("외부 자료와 대조한 것만 FACT_CHECK")
    void factChecks() {
        assertThat(CandidateType.from(RiskCategory.FACT_ERROR)).isEqualTo(CandidateType.FACT_CHECK);
        assertThat(CandidateType.from(RiskCategory.MISINFORMATION)).isEqualTo(CandidateType.FACT_CHECK);
        assertThat(CandidateType.from(RiskCategory.UNVERIFIED_CLAIM)).isEqualTo(CandidateType.FACT_CHECK);
    }

    @Test
    @DisplayName("나머지는 전부 다시 읽어볼 표현")
    void everythingElseIsSpeechReview() {
        assertThat(CandidateType.from(RiskCategory.MOCKERY)).isEqualTo(CandidateType.SPEECH_REVIEW);
        assertThat(CandidateType.from(RiskCategory.UNFAMILIAR_CONTEXT)).isEqualTo(CandidateType.SPEECH_REVIEW);
        assertThat(CandidateType.from(RiskCategory.TIMING_SENSITIVE)).isEqualTo(CandidateType.SPEECH_REVIEW);
    }

    @Test
    @DisplayName("모든 카테고리에 대응되는 값이 있다")
    void coversEveryCategory() {
        for (RiskCategory category : RiskCategory.values()) {
            assertThat(CandidateType.from(category)).as("카테고리 %s", category).isNotNull();
        }
    }

    @Test
    @DisplayName("null 이어도 터지지 않는다")
    void handlesNull() {
        assertThat(CandidateType.from(null)).isEqualTo(CandidateType.SPEECH_REVIEW);
    }
}
