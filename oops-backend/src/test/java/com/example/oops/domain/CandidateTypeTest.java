package com.example.oops.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 내부 카테고리 22개를 사용자에게 보여줄 4가지로 줄이는 규칙.
 *
 * 이 매핑이 틀어지면 프론트가 엉뚱한 카드를 그린다.
 * 카테고리를 새로 추가할 때 여기 안 넣으면 전부 SPEECH_REVIEW 로 떨어지므로,
 * 그걸 잡아내는 것이 이 테스트의 목적이다.
 */
class CandidateTypeTest {

    @Test
    @DisplayName("외부 자료와 대조한 것은 FACT_ENTITY")
    void factChecks() {
        assertThat(CandidateType.from(RiskCategory.FACT_ERROR, TimelineEventType.SPEECH))
                .isEqualTo(CandidateType.FACT_ENTITY);
        assertThat(CandidateType.from(RiskCategory.MISINFORMATION, TimelineEventType.SPEECH))
                .isEqualTo(CandidateType.FACT_ENTITY);
        assertThat(CandidateType.from(RiskCategory.UNVERIFIED_CLAIM, TimelineEventType.SPEECH))
                .isEqualTo(CandidateType.FACT_ENTITY);
    }

    @Test
    @DisplayName("배경을 알려주는 것은 CONTEXT_REFERENCE")
    void contextChecks() {
        assertThat(CandidateType.from(RiskCategory.TIMING_SENSITIVE, TimelineEventType.SPEECH))
                .isEqualTo(CandidateType.CONTEXT_REFERENCE);
        assertThat(CandidateType.from(RiskCategory.UNFAMILIAR_CONTEXT, TimelineEventType.SPEECH))
                .isEqualTo(CandidateType.CONTEXT_REFERENCE);
    }

    @Test
    @DisplayName("맥락 참고는 화면에서 잡혀도 CONTEXT_REFERENCE 다")
    void contextStaysContextEvenOnScreen() {
        // context-check 는 화면 자막에서도 후보를 만든다.
        // 이걸 SCREEN_TEXT_REVIEW 로 보내면 참고 자료가 붙은 카드인데
        // 프론트는 근거 없는 카드로 그리게 된다.
        assertThat(CandidateType.from(RiskCategory.TIMING_SENSITIVE, TimelineEventType.CAPTION))
                .isEqualTo(CandidateType.CONTEXT_REFERENCE);
    }

    @Test
    @DisplayName("나머지는 발언에서 나왔는지 화면에서 나왔는지로 가른다")
    void splitsBySource() {
        assertThat(CandidateType.from(RiskCategory.MOCKERY, TimelineEventType.SPEECH))
                .isEqualTo(CandidateType.SPEECH_REVIEW);
        assertThat(CandidateType.from(RiskCategory.MOCKERY, TimelineEventType.CAPTION))
                .isEqualTo(CandidateType.SCREEN_TEXT_REVIEW);
    }

    @Test
    @DisplayName("모든 카테고리에 대응되는 값이 있다")
    void coversEveryCategory() {
        for (RiskCategory category : RiskCategory.values()) {
            assertThat(CandidateType.from(category, TimelineEventType.SPEECH))
                    .as("카테고리 %s", category)
                    .isNotNull();
        }
    }
}
