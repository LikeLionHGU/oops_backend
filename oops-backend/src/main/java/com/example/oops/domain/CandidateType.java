package com.example.oops.domain;

/**
 * 검토 후보의 종류. 명세 §15-2 CandidateType.
 *
 * type(SPEECH/CAPTION)은 "어디서 나온 말인가"를 뜻하고,
 * candidateType 은 "왜 다시 확인해야 하는가"를 뜻한다.
 * 프론트는 이 값으로 카드 모양과 안내 문구를 정한다.
 *
 * 내부 RiskCategory 는 22개로 잘게 나뉘어 있는데 그건 분석기가 쓰는 값이다.
 * 사용자에게는 이 4가지(+예약 2개)로만 보여준다.
 */
public enum CandidateType {

    /** 발언에서 다시 볼 표현·주장·대상 */
    SPEECH_REVIEW,

    /** 화면 글자에서 다시 볼 표현·주장·대상 */
    SCREEN_TEXT_REVIEW,

    /** 인물·회사·날짜·숫자를 외부 자료와 대조한 것 */
    FACT_ENTITY,

    /** 지금 시점의 사건·배경을 외부 자료로 참고한 것 */
    CONTEXT_REFERENCE,

    /** 화면 자료 확인. P1 예약값 — 현재 만들지 않는다 */
    VISUAL_REFERENCE,

    /** 발언↔편집 자막 일치성. MVP 제외 예약값 — 분석기가 꺼져 있다 */
    CAPTION_CONSISTENCY;

    /**
     * 내부 카테고리를 사용자에게 보여줄 분류로 바꾼다.
     *
     * 분석기 이름을 따로 저장하지 않고 카테고리에서 끌어내는 이유는,
     * 카테고리와 이벤트 유형만으로 어느 분석기가 만들었는지 결정되기 때문이다.
     * (같은 카테고리를 두 분석기가 만들면 발언/화면으로 갈린다)
     */
    public static CandidateType from(RiskCategory category, TimelineEventType eventType) {
        if (category == null) {
            return SPEECH_REVIEW;
        }
        return switch (category) {
            // 외부 자료와 대조한 것
            case FACT_ERROR, MISINFORMATION, UNVERIFIED_CLAIM -> FACT_ENTITY;

            // 지금 시점의 배경을 알려주는 것
            case TIMING_SENSITIVE, UNFAMILIAR_CONTEXT -> CONTEXT_REFERENCE;

            // 아직 안 쓰는 값들
            case CAPTION_MISMATCH -> CAPTION_CONSISTENCY;
            case GESTURE -> VISUAL_REFERENCE;

            // 나머지는 어디서 나왔는지로 가른다
            default -> eventType == TimelineEventType.CAPTION
                    ? SCREEN_TEXT_REVIEW : SPEECH_REVIEW;
        };
    }
}
