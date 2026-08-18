package com.example.oops.domain;

/**
 * 검토 후보의 종류. 명세 v2.1 §10.
 *
 * 사용자에게 보여주는 분류는 **두 가지뿐**입니다.
 *
 *   SPEECH_REVIEW  다시 읽어볼 표현
 *   FACT_CHECK     외부 자료와 대조가 필요한 내용
 *
 * 예전에는 네 가지(발언/화면글자/사실/맥락)였는데,
 * 사용자 입장에서 "화면에서 나왔는지 발언에서 나왔는지" 는
 * candidateType 이 아니라 type(SPEECH/CAPTION) 이 답할 문제였습니다.
 * 같은 정보를 두 필드가 나눠 갖고 있으면 화면 분기만 복잡해집니다.
 *
 *   type          어디서 나왔나  (SPEECH = 발언, CAPTION = 화면 글자)
 *   candidateType 왜 확인하나   (표현 검토인가, 사실 확인인가)
 */
public enum CandidateType {

    /** 다시 읽어볼 표현. 발언이든 화면 글자든 */
    SPEECH_REVIEW,

    /** 외부 자료와 대조가 필요한 내용 */
    FACT_CHECK;

    /**
     * 내부 카테고리를 사용자에게 보여줄 분류로 바꾼다.
     *
     * 외부 자료를 찾아본 것만 FACT_CHECK 다.
     * 나머지는 전부 "다시 읽어볼 표현" 으로 묶는다.
     */
    public static CandidateType from(RiskCategory category) {
        if (category == null) {
            return SPEECH_REVIEW;
        }
        return switch (category) {
            case FACT_ERROR, MISINFORMATION, UNVERIFIED_CLAIM -> FACT_CHECK;
            default -> SPEECH_REVIEW;
        };
    }
}
