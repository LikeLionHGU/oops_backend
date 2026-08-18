package com.example.oops.domain;

/**
 * 확인할 주장의 성격.
 *
 * 성격에 따라 어떤 자료를 먼저 봐야 하는지가 달라진다.
 * 모든 사실을 당사자 발언으로 검증하려 들면 오히려 틀린다.
 */
public enum ClaimType {

    /** 본인의 생각·의도·경험. "그때 이런 마음이었다" */
    PERSONAL_STATEMENT,

    /** 연도·날짜·기간 */
    DATE,

    /** 수치·통계·금액 */
    NUMBER,

    /** 인물·회사·기관·작품의 이름이나 관계 */
    ENTITY,

    /** 사건과 그 사건에 대한 서술 */
    EVENT,

    /** 그 밖의 확인 가능한 사실 */
    GENERAL_FACT;

    public static ClaimType fromOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return GENERAL_FACT;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return GENERAL_FACT;
        }
    }
}
