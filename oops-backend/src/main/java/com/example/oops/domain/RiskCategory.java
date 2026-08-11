package com.example.oops.domain;

/**
 * 논란의 유형. 프론트에서 필터/뱃지로 쓰기 좋게 라벨을 같이 들고 있는다.
 * 새 분석기를 붙일 때 여기에 카테고리를 추가하면 된다.
 */
public enum RiskCategory {

    // 발언 리스크
    MOCKERY("조롱"),
    BELITTLEMENT("비하"),
    GENERALIZATION("과도한 일반화"),
    SENSITIVE_TOPIC("민감 주제"),
    TIMING_SENSITIVE("시의성 논란"),   // 지금 시점에 다루기 곤란한 이슈
    HATE_SPEECH("혐오 표현"),
    DISCRIMINATION("차별적 발언"),
    PROFANITY("욕설/비속어"),
    SEXUAL("선정적 내용"),
    VIOLENCE("폭력적 내용"),
    MISINFORMATION("사실관계 논란"),
    PRIVACY("개인정보 노출"),
    ADVERTISING("광고/협찬 미표기"),

    // 화면(OCR) 관련
    CAPTION_MISMATCH("자막과 발언 불일치"),
    SCREEN_TEXT("화면 자막 문제"),

    // 추후
    GESTURE("부적절한 제스처"),
    COMMENT_BACKLASH("댓글 여론 악화");

    private final String label;

    RiskCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static RiskCategory fromOrDefault(String value, RiskCategory fallback) {
        if (value == null) return fallback;
        for (RiskCategory category : values()) {
            if (category.name().equalsIgnoreCase(value.trim())) {
                return category;
            }
        }
        return fallback;
    }
}
