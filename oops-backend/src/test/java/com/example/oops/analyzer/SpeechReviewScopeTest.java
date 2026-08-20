package com.example.oops.analyzer;

import com.example.oops.domain.RiskCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발언 검토의 범위를 양쪽에서 잠가 둔다.
 *
 * 여기 있는 값들은 전부 실제 사고에서 나왔다.
 *
 * **넓혔다가 데인 쪽.**
 * 39분짜리 대화 영상에서 후보 134건이 나왔다.
 * '순대' 가 커뮤니티 은어로 14번, '너' 가 집단 일반화로 4번 올라왔다.
 *
 * **좁혔다가 데인 쪽.**
 * 그래서 유형을 셋으로 줄였더니 "할머니 맛" 과 소개 중인 가게의 메뉴 평가가
 * 통째로 안 잡혔다. 발언에서 BELITTLEMENT 를 만들 수 있는 곳이 여기뿐이라
 * 지운 순간 아무도 안 맡는 상태가 됐다.
 *
 * 그래서 이 테스트는 **"넓히지 마라" 와 "좁히지 마라" 를 같이** 잠근다.
 * 한쪽만 잠그면 반대쪽으로 또 넘어진다.
 */
class SpeechReviewScopeTest {

    @Test
    @DisplayName("발언 검토는 다섯 영역만 만든다")
    void allowedCategoriesAreExactlyFive() {
        assertThat(SpeechReviewAnalyzer.ALLOWED).containsExactlyInAnyOrder(
                RiskCategory.SENSITIVE_TOPIC,
                RiskCategory.GENERALIZATION,
                RiskCategory.UNFAMILIAR_CONTEXT,
                RiskCategory.BELITTLEMENT,
                RiskCategory.DISCRIMINATION);
    }

    @Test
    @DisplayName("소개 대상 평가는 범위 안에 있어야 한다")
    void targetReviewMustStayInScope() {
        // README 가 말하는 확인 지점 세 축 중 하나가 '소개 중인 대상에 대한 평가' 다.
        // 이걸 빼면 "너무 특색이 없어가지고", "할머니 맛" 이 아무 데도 안 걸린다.
        // 룰 기반 RiskRuleEngine 에는 비하 키워드가 없다. 열거할 수 없는 유형이다.
        // 즉 여기서 빼는 것은 다른 곳에 넘기는 게 아니라 기능을 없애는 것이다.
        assertThat(SpeechReviewAnalyzer.ALLOWED).contains(RiskCategory.BELITTLEMENT);
    }

    @Test
    @DisplayName("속성을 근거로 한 차별은 범위 안에 있어야 한다")
    void discriminationMustStayInScope() {
        // 국적·성별·장애를 근거로 삼은 발언이다.
        // GENERALIZATION 은 '집단 전체를 묶었을 때' 만 걸리므로 이걸 대신하지 못한다.
        // 룰 사전에는 '짱깨' 같은 노골적 단어만 있어서 안전망이 되지 못한다.
        assertThat(SpeechReviewAnalyzer.ALLOWED).contains(RiskCategory.DISCRIMINATION);
    }

    @Test
    @DisplayName("조롱과 사실 오류는 여기서 만들지 않는다")
    void mockeryAndFactErrorAreOutOfScope() {
        // MOCKERY: 자학과 농담이 조롱으로 올라갔다. 봐야 할 것은 BELITTLEMENT 로 잡힌다.
        //          병합 단계에서 둘은 같은 PUTDOWN 그룹이라 카드로는 어차피 한 장이다.
        // FACT_ERROR: 이 분석기는 검색을 하지 않아 근거를 붙일 수 없다. entity-check 몫이다.
        assertThat(SpeechReviewAnalyzer.ALLOWED)
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
    @DisplayName("평가 대상이 되는 실체는 막지 않는다")
    void concreteTargetsAreNotBlocked() {
        // '할머니' 를 여기 넣으면 "할머니 맛" 이 다시 사라진다.
        // 친족어라도 평가 대상이 될 수 있으므로 대상 차단 목록에 넣지 마라.
        assertThat(SpeechReviewAnalyzer.GENERIC_TARGETS)
                .doesNotContain("할머니", "가게", "메뉴", "김치찌개", "사장님");
    }

    @Test
    @DisplayName("한 창에서 받는 건수에 상한이 있다")
    void windowHasCap() {
        assertThat(SpeechReviewAnalyzer.MAX_PER_WINDOW)
                .isGreaterThan(0)
                .isLessThanOrEqualTo(3);
    }
}
