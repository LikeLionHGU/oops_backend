package com.example.oops.analyzer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 커뮤니티 은어 탐지.
 *
 * 이 기능이 필요했던 계기는 "무섭노" 같은 어미 하나로 논란이 된 실제 사례였다.
 * LLM 은 사투리와 겹친다는 이유로 이런 걸 그냥 넘긴다. 그래서 사전을 따로 뒀다.
 *
 * 여기서 지켜야 할 균형이 있다.
 *   못 잡으면 → 만든 이유가 없어진다
 *   과하게 잡으면 → 경상도 사투리를 쓰는 출연자마다 경고가 뜬다
 */
class CommunitySlangRulesTest {

    private CommunitySlangRules rules;

    @BeforeEach
    void setUp() {
        rules = new CommunitySlangRules();
    }

    @Test
    @DisplayName("사전에 있는 은어를 잡는다")
    void detectsKnownSlang() {
        assertThat(rules.detect("그건 좀 운지 아니냐"))
                .isNotEmpty()
                .anySatisfy(hit -> assertThat(hit.target()).isEqualTo("운지"));
    }

    @Test
    @DisplayName("문장 끝의 ~노 어미를 잡는다")
    void detectsNoEnding() {
        assertThat(rules.detect("이거 진짜 무섭노"))
                .anySatisfy(hit -> assertThat(hit.target()).isEqualTo("~노 어미"));
    }

    @Test
    @DisplayName("사투리 신호가 같이 있으면 ~노 어미를 넘긴다")
    void skipsDialect() {
        // "머하노" 는 사투리 화자가 일상적으로 쓰는 말이다.
        // 이걸 잡으면 부산 출신 출연자 영상이 경고 범벅이 된다.
        assertThat(rules.detect("니 머하노"))
                .noneSatisfy(hit -> assertThat(hit.target()).isEqualTo("~노 어미"));
    }

    @Test
    @DisplayName("평범한 문장은 아무것도 잡지 않는다")
    void ignoresNormalText() {
        assertThat(rules.detect("오늘 날씨가 정말 좋네요")).isEmpty();
        assertThat(rules.detect("이 가게는 삼십 년 됐습니다")).isEmpty();
    }

    @Test
    @DisplayName("빈 입력에도 터지지 않는다")
    void handlesEmpty() {
        assertThat(rules.detect(null)).isEmpty();
        assertThat(rules.detect("   ")).isEmpty();
    }

    @Test
    @DisplayName("점수를 낮게 준다 — 판정이 아니라 확인 신호다")
    void scoresLow() {
        // 높게 주면 '우선 확인' 으로 올라가 판정처럼 보인다.
        // 우리가 하는 일은 "이런 맥락이 있다" 고 알려주는 것이지 잘못됐다고 하는 게 아니다.
        assertThat(rules.detect("이거 진짜 무섭노"))
                .allSatisfy(hit -> assertThat(hit.score()).isLessThan(0.7));
    }
}
