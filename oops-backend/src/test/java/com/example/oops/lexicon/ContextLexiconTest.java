package com.example.oops.lexicon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 맥락 표현 사전.
 *
 * **이 테스트의 목적은 잡는 것보다 안 잡는 것을 지키는 데 있다.**
 *
 * 사전에는 '7시', '수박', '포도', '초딩' 처럼 일상어가 들어 있다.
 * 단어만 보고 카드를 만들면 영상 하나에 오탐이 수십 개 쏟아지고,
 * 제작자는 두 번째 영상부터 이 도구를 안 쓴다.
 * 그래서 일반 용법을 걸러내는 쪽을 더 촘촘히 확인한다.
 */
class ContextLexiconTest {

    private ContextLexicon lexicon;

    @BeforeEach
    void setUp() {
        lexicon = new ContextLexicon();
        lexicon.load();
    }

    private boolean matches(String text) {
        return !lexicon.match(text).isEmpty();
    }

    @Test
    @DisplayName("사전이 로드된다")
    void loads() {
        assertThat(lexicon.size()).isGreaterThanOrEqualTo(50);
    }

    // ---------- 안 잡아야 하는 것 ----------

    @Test
    @DisplayName("시간 이야기는 잡지 않는다")
    void ignoresClockTime() {
        assertThat(matches("저녁 7시에 만나기로 했어요")).isFalse();
        assertThat(matches("오전 7시 출발입니다")).isFalse();
    }

    @Test
    @DisplayName("음식 이야기는 잡지 않는다")
    void ignoresFood() {
        assertThat(matches("여름엔 역시 수박이 맛있죠")).isFalse();
        assertThat(matches("낙지볶음 먹으러 갔어요")).isFalse();
        assertThat(matches("홍어회는 삭혀야 제맛이에요")).isFalse();
        assertThat(matches("청포도 주스 한 잔 주세요")).isFalse();
        assertThat(matches("닭 통구이 맛집이라고 해서요")).isFalse();
    }

    @Test
    @DisplayName("일상 표현은 잡지 않는다")
    void ignoresEverydayUse() {
        assertThat(matches("우리 조카가 초등학교 3학년이에요")).isFalse();
        assertThat(matches("한남동에서 저녁 먹었어요")).isFalse();
        assertThat(matches("사업에 실패했다가 재기에 성공했죠")).isFalse();
        assertThat(matches("설거지는 제가 할게요")).isFalse();
        assertThat(matches("기타 운지법부터 배웠어요")).isFalse();
    }

    @Test
    @DisplayName("경상도 사투리는 잡지 않는다")
    void ignoresDialect() {
        // 이걸 잡으면 부산·대구 출신 출연자 영상이 경고 범벅이 된다
        assertThat(matches("니 머하노")).isFalse();
        assertThat(matches("밥은 뭇나 뭐하노")).isFalse();
    }

    @Test
    @DisplayName("평범한 문장은 아무것도 잡지 않는다")
    void ignoresPlainText() {
        assertThat(matches("오늘 날씨가 정말 좋네요")).isFalse();
        assertThat(matches("이 가게는 삼십 년 됐습니다")).isFalse();
    }

    // ---------- 잡아야 하는 것 ----------

    @Test
    @DisplayName("특수 맥락 신호가 같이 있으면 잡는다")
    void catchesWithContextHint() {
        assertThat(matches("저 의원도 결국 수박이더라고")).isTrue();
        assertThat(matches("그 지역 사람들, 홍어 아니냐")).isTrue();
    }

    @Test
    @DisplayName("일반 용법이 없는 표현은 그대로 잡는다")
    void catchesDirectTerms() {
        assertThat(matches("전라디언 소리까지 나오더라")).isTrue();
        assertThat(matches("요즘 틀딱들이 말이야")).isTrue();
        assertThat(matches("급식충 수준이네")).isTrue();
    }

    @Test
    @DisplayName("문장 끝의 ~노 어미를 잡는다")
    void catchesNoEnding() {
        assertThat(matches("이거 진짜 무섭노")).isTrue();
    }

    @Test
    @DisplayName("정치 표현을 한쪽만 담지 않는다")
    void coversBothSides() {
        // 한쪽만 잡으면 그 자체가 편향이다
        assertThat(matches("문재앙 소리 나오던데")).isTrue();
        assertThat(matches("굥 이야기가 또 나오네")).isTrue();
        assertThat(matches("대깨문이라고 부르더라")).isTrue();
        assertThat(matches("대깨윤이라고 부르더라")).isTrue();
    }

    // ---------- 확신도 ----------

    @Test
    @DisplayName("점수를 낮게 준다 — 판정이 아니라 확인 신호다")
    void scoresLow() {
        lexicon.match("전라디언 소리까지 나오더라")
                .forEach(m -> assertThat(m.score()).isLessThan(0.8));
    }

    @Test
    @DisplayName("맥락 신호가 같이 있으면 조금 높게 준다")
    void boostsWithContextHint() {
        double withHint = lexicon.match("저 의원도 결국 수박이더라고").get(0).score();
        assertThat(withHint).isGreaterThan(ContextTriggerMode.POLITICAL_CONTEXT.baseScore());
    }

    @Test
    @DisplayName("빈 입력에도 터지지 않는다")
    void handlesEmpty() {
        assertThat(lexicon.match(null)).isEmpty();
        assertThat(lexicon.match("   ")).isEmpty();
    }
}
