package com.example.oops.domain;

/**
 * 참고 자료가 원출처에 얼마나 가까운지.
 *
 * 이걸 나눈 이유는 "당사자가 뭐라고 말했는가" 를 확인할 때
 * 2차 요약 기사보다 당사자 발언을 인용한 기사가 낫기 때문이다.
 *
 * **진실성 순위가 아니라 검색·표시 우선순위다.**
 * 당사자 말이 항상 맞는 것도 아니고, 언론 보도가 항상 틀린 것도 아니다.
 * 주장의 성격에 맞는 자료를 위로 올리기 위한 값이다.
 */
public enum ReferenceSourceType {

    /** 당사자의 인터뷰 전문, 직접 쓴 글, 공식 개인 발언 */
    PRIMARY_SOURCE("당사자 자료", 100),

    /** 정부·기관·기업의 공식 자료 */
    OFFICIAL_SOURCE("공식 자료", 90),

    /** 언론 기사인데 당사자의 말을 직접 인용한 것 */
    DIRECT_QUOTE_SOURCE("인터뷰·직접 인용", 80),

    /** 일반적인 언론 보도 */
    REPUTABLE_MEDIA("언론 보도", 60),

    /** 다른 자료를 재정리한 2차 자료 */
    SECONDARY_SOURCE("2차 자료", 30);

    private final String label;
    private final int priority;

    ReferenceSourceType(String label, int priority) {
        this.label = label;
        this.priority = priority;
    }

    public String getLabel() {
        return label;
    }

    public int getPriority() {
        return priority;
    }

    /**
     * 주장의 성격에 맞는 자료 우선순위.
     *
     * 이게 핵심이다. 모든 사실을 당사자 발언으로 검증하면 안 된다.
     *   "내가 이런 의도였다"  → 당사자 말이 가장 적합하다
     *   "판매량이 5천만 장"   → 당사자 말보다 공식 통계가 낫다
     */
    public int priorityFor(ClaimType claimType) {
        if (claimType == null) {
            return priority;
        }
        return switch (claimType) {
            // 본인의 생각·의도·경험은 본인 말이 원본이다
            case PERSONAL_STATEMENT -> switch (this) {
                case PRIMARY_SOURCE -> 100;
                case DIRECT_QUOTE_SOURCE -> 95;
                case REPUTABLE_MEDIA -> 60;
                case OFFICIAL_SOURCE -> 50;
                case SECONDARY_SOURCE -> 20;
            };
            // 숫자·날짜는 당사자 기억보다 공식 기록이 정확하다
            case DATE, NUMBER -> switch (this) {
                case OFFICIAL_SOURCE -> 100;
                case PRIMARY_SOURCE -> 80;
                case REPUTABLE_MEDIA -> 70;
                case DIRECT_QUOTE_SOURCE -> 60;
                case SECONDARY_SOURCE -> 25;
            };
            default -> priority;
        };
    }
}
