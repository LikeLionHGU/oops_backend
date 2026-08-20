package com.example.oops.analyzer;

import com.example.oops.domain.RiskCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발언 검토가 세 영역 밖으로 새지 않는지 잠가 둔다.
 *
 * 여기 있는 값들은 전부 실제 오탐에서 나왔다.
 * 39분짜리 대화 영상에서 후보 134건이 나왔고,
 * '순대' 가 커뮤니티 은어로 14번, '너' 가 집단 일반화로 4번 올라왔다.
 *
 * 나중에 "더 많이 잡게 하자" 는 이유로 범위를 넓히려 할 때
 * 이 테스트가 먼저 깨지면서 그때 무슨 일이 있었는지 알려주는 것이 목적이다.
 */
class SpeechReviewScopeTest {

    @Test
    @DisplayName("발언 검토는 세 영역만 만든다")
    void allowedCategoriesAreExactlyThree() {
        assertThat(SpeechReviewAnalyzer.ALLOWED).containsExactlyInAnyOrder(
                RiskCategory.SENSITIVE_TOPIC,
                RiskCategory.GENERALIZATION,
                RiskCategory.UNFAMILIAR_CONTEXT);
    }

    @Test
    @DisplayName("비하·조롱은 여기서 만들지 않는다")
    void belittlementAndMockeryAreOutOfScope() {
        // "못해", "공부나 해야겠다" 같은 자기 얘기가 비하 카드로 올라갔던 자리다.
        assertThat(SpeechReviewAnalyzer.ALLOWED)
                .doesNotContain(RiskCategory.BELITTLEMENT)
                .doesNotContain(RiskCategory.MOCKERY)
                .doesNotContain(RiskCategory.FACT_ERROR)
                .doesNotContain(RiskCategory.SEXUAL);
    }

    @Test
    @DisplayName("대명사와 지시어는 대상이 될 수 없다")
    void pronounsAreBlocked() {
        assertThat(SpeechReviewAnalyzer.GENERIC_TARGETS)
                .contains("너", "얘", "걔", "친구", "사람", "표현", "이야기");
    }

    @Test
    @DisplayName("진짜 집단을 가리키는 말은 막지 않는다")
    void realGroupsAreNotBlocked() {
        // 이걸 막으면 "요즘 20대는 다 책임감이 없어" 를 놓친다.
        // 성질이 붙었는지 판단하는 건 프롬프트 몫이고, 여기서 미리 자르면 안 된다.
        assertThat(SpeechReviewAnalyzer.GENERIC_TARGETS)
                .doesNotContain("남자", "여자", "20대", "경상도 사람", "공무원", "중국인");
    }

    @Test
    @DisplayName("한 창에서 받는 건수에 상한이 있다")
    void windowHasCap() {
        assertThat(SpeechReviewAnalyzer.MAX_PER_WINDOW)
                .isGreaterThan(0)
                .isLessThanOrEqualTo(3);
    }
}
