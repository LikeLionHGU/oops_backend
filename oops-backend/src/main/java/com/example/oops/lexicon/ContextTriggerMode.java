package com.example.oops.lexicon;

/**
 * 표현이 왜 확인 대상인지의 성격. 이 값에 따라 확인 방식과 우선순위가 달라진다.
 *
 * 이걸 나눠 둔 이유는, 전부 같은 무게로 다루면 안 되기 때문이다.
 * '틀딱' 은 편집자도 보면 안다. 우리가 잘해야 하는 건
 * '7시', '포도', '수박' 처럼 **모르면 그냥 지나치는** 쪽이다.
 */
public enum ContextTriggerMode {

    /** 일반 의미와 특수 의미가 함께 있다. 앞뒤를 봐야 안다 */
    CONTEXT_REQUIRED(0.45, true),

    /** 논란은 있었지만 의미·기원에 이견이 있다. 단정하면 안 된다 */
    DISPUTED_CONTEXT(0.35, true),

    /** 과거에 잘못 분류된 적이 있다. 특히 조심한다 */
    DISPUTED_GUARD(0.30, true),

    /** 특정 사건·사망과 연결된다 */
    HISTORICAL_EVENT(0.55, true),

    /** 정치 상황에서 계파·지지층을 가리키게 된다 */
    POLITICAL_CONTEXT(0.45, true),

    /** 정치 구호·정체성 표시. 좋고 나쁨을 판단하지 않는다 */
    POLITICAL_SIGNAL(0.30, false),

    /** 시간이 지나며 의미가 변했다 */
    MEANING_SHIFT(0.40, true),

    /** 의미가 비교적 직접적이다. 편집자도 알아챌 가능성이 높아 우선순위가 낮다 */
    SAFETY_NET(0.35, false);

    private final double baseScore;
    private final boolean core;

    ContextTriggerMode(double baseScore, boolean core) {
        this.baseScore = baseScore;
        this.core = core;
    }

    /**
     * 기본 확신도. 전부 낮게 잡는다.
     * 우리가 하는 일은 판정이 아니라 "이런 맥락이 있다" 고 알려주는 것이다.
     */
    public double baseScore() {
        return baseScore;
    }

    /** 이 도구가 잘해야 하는 영역인지. 아니면 안전망이다 */
    public boolean isCore() {
        return core;
    }
}
