package com.example.oops.analyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 메뉴판을 검토 대상에서 빼는 판정.
 *
 * 이 테스트의 절반은 **걸러내면 안 되는 것**이다.
 * 문턱을 낮추면 진짜 편집 자막이 조용히 빠지는데, 그건 훨씬 나쁘다.
 * "봤는데 없다" 와 "보지도 못했다" 를 구분해야 하는 도구에서
 * 검토 대상을 몰래 줄이는 것은 후자를 만드는 일이다.
 */
class ScreenTextShapeTest {

    /**
     * 실제로 '정치인' 오탐을 만든 프레임의 OCR 결과 그대로다.
     *
     * 식당 메뉴판이고, 안에 '낙지전곰'(낙지전골 오인식)이 있다.
     * 사전의 '낙지' 항목이 여기 걸려서 메뉴판 사진과 함께
     * "정치 맥락에서 특정 인물을 가리키는 표현" 카드가 나갔다.
     */
    private static final String REAL_MENU_BOARD =
            "MAH니2뉴 김치찌개 1100 O오리판아레 된장찌개 11000 리문R유 동태찌개 "
            + "돼지주물력 20 11000 순두부찌개 소불고기 11000 12000 낙지전곰 주분 "
            + "절배주교주가뤄국내신 늘근 여기는";

    @Test
    @DisplayName("'정치인' 오탐을 만든 그 메뉴판을 걸러낸다")
    void filtersTheMenuBoardThatCausedTheFalsePositive() {
        assertThat(ScreenTextShape.looksLikePriceList(REAL_MENU_BOARD)).isTrue();
    }

    @Test
    @DisplayName("값이 나열된 것은 가격표로 본다")
    void priceListsAreDetected() {
        assertThat(ScreenTextShape.looksLikePriceList("김치찌개 8000 된장찌개 8000 공기밥 1000")).isTrue();
        assertThat(ScreenTextShape.looksLikePriceList("삼겹살 14000 2인분부터")).isTrue();
        assertThat(ScreenTextShape.looksLikePriceList("낙지전골 50000 원산지 국내산")).isTrue();
    }

    @Test
    @DisplayName("음식 이름만 여럿 나열돼도 메뉴판으로 본다")
    void foodListWithoutPricesIsDetected() {
        // OCR 이 숫자를 통째로 흘리는 경우가 흔하다.
        assertThat(ScreenTextShape.looksLikePriceList(
                "김치찌개 된장찌개 순두부찌개 부대찌개 제육볶음")).isTrue();
    }

    @Test
    @DisplayName("편집 자막은 걸러내지 않는다")
    void editorialCaptionsSurvive() {
        // 여기가 깨지면 검토 대상이 조용히 줄어든다. 오탐보다 나쁘다.
        for (String caption : new String[]{
                "너무 특색이 없어가지고",
                "독도는 일본땅",
                "2019년에 100만 명을 넘었습니다",   // 숫자 둘이지만 자막이다
                "그 회사는 2020년에 설립됐다",
                "오조오억 개 있었다니까",
                "이게 왜 정치판에서 나오노",
                "무섭노",
                "구독과 좋아요 부탁드립니다",
                "2019년에 삼겹살집을 열었다",      // 음식 이름 + 연도. 자막이다
                "1인분 8000원",                    // 값 하나 + 메뉴판 표시. 음식 이름이 없다
                "할머니 살을 뜯는 것 같은 맛",
                "롯데리아 버거 진짜 못 먹겠다"}) {  // 이게 걸러지면 브랜드 비하를 놓친다
            assertThat(ScreenTextShape.looksLikePriceList(caption))
                    .as("자막인데 걸러졌다: %s", caption)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("음식 이야기가 담긴 자막 한 줄은 걸러내지 않는다")
    void singleFoodMentionSurvives() {
        // 메뉴판인지 가르는 것이 목적이다. 음식 얘기를 지우는 게 아니다.
        assertThat(ScreenTextShape.looksLikePriceList("김치찌개 진짜 맛없다")).isFalse();
        assertThat(ScreenTextShape.looksLikePriceList("낙지전골 시켰어요")).isFalse();
    }

    @Test
    @DisplayName("빈 값에도 안 터진다")
    void handlesEmpty() {
        assertThat(ScreenTextShape.looksLikePriceList(null)).isFalse();
        assertThat(ScreenTextShape.looksLikePriceList("   ")).isFalse();
    }
}
