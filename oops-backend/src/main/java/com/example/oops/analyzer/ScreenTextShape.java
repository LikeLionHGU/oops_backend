package com.example.oops.analyzer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 화면 글자가 **편집 자막인지 환경 글자인지** 모양만 보고 가른다.
 *
 * 기획서 §24 의 최우선 과제는 편집 자막과 간판·메뉴판을 구분하는 것이다.
 * 제대로 하려면 위치·지속·발언과의 관계를 다 봐야 하는데(격차분석 §3),
 * 그중 **가격표만은 모양으로 거의 확실하게 걸러진다.** 값이 큰 것부터 한다.
 *
 * 실제로 겪은 일이다. 식당 메뉴판을 OCR 이 읽었고 거기 '낙지전골' 이 있었다.
 * 사전의 '낙지' 항목(정치 맥락)이 걸렸고, 억제 신호에 '전골' 이 없어서
 * 통과했고, AI 는 앞뒤가 온통 메뉴라 판단할 근거가 없는데도 특수 용법이라
 * 답했다. 그래서 **'정치인' 카드가 메뉴판 사진과 함께 나갔다.**
 *
 * 억제 신호를 하나씩 채우는 것으로는 못 막는다. 메뉴판에는 음식 이름이
 * 수십 개 있고 OCR 이 글자를 흘려서('낙지전골' → '낙지전곰') 예측이 안 된다.
 * 애초에 **가격표를 검토 대상에서 빼는 것**이 맞다.
 *
 * 놓치는 것을 감수한다. 메뉴판에 진짜 문제가 박혀 있을 수도 있지만,
 * 그건 편집자가 넣은 문구가 아니라 가게가 만든 것이다.
 * 이 도구가 짚어야 할 사각지대가 아니다.
 */
public final class ScreenTextShape {

    private ScreenTextShape() {}

    /** 값처럼 보이는 숫자. 3~6자리. 전화번호·연도와 섞이므로 개수로 판단한다. */
    private static final Pattern PRICE = Pattern.compile("(?<!\\d)\\d{3,6}(?!\\d)");

    /** 메뉴판·가격표에만 거의 확실히 있는 말 */
    private static final Pattern MENU_MARKER = Pattern.compile(
            "인분|메뉴|백반|세트|공기|추가|원산지|국내산|중국산|테이크아웃|포장");

    /** 음식 이름의 꼬리. 하나로는 부족하고 여러 개 모이면 메뉴판이다. */
    private static final Pattern FOOD_TAIL = Pattern.compile(
            "찌개|전골|볶음|구이|튀김|무침|조림|비빔|국밥|정식|탕수|삼겹|불고기|칼국수|냉면");

    /**
     * 가격표·메뉴판으로 보이는가.
     *
     * 편집 자막이 잘못 걸리지 않게 문턱을 높게 뒀다.
     * "2019년에 100만 명" 처럼 숫자 둘이 있는 자막은 통과해야 한다.
     * 그래서 숫자만으로 판정할 때는 셋 이상을 요구한다.
     */
    public static boolean looksLikePriceList(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        int prices = count(PRICE, text);
        int foods = count(FOOD_TAIL, text);
        boolean marker = MENU_MARKER.matcher(text).find();

        // 값이 셋 이상 나열되면 그건 목록이다. 자막은 그렇게 안 생겼다.
        if (prices >= 3) return true;
        // 값이 둘인데 음식 이름이나 메뉴판 표시가 함께 있으면 메뉴판이다.
        if (prices >= 2 && (marker || foods >= 1)) return true;
        // 값 하나여도 '2인분부터', '원산지 국내산' 처럼 메뉴판에만 있는 말과
        // 음식 이름이 같이 있으면 메뉴판 조각이다. 프레임이 잘려 들어온 경우다.
        if (prices >= 1 && marker && foods >= 1) return true;
        // 값을 못 읽었어도 음식 이름이 넷 이상 나열되면 메뉴판이다.
        return foods >= 4;
    }

    private static int count(Pattern p, String text) {
        Matcher m = p.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }
}
