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
    UNFAMILIAR_CONTEXT("배경 확인 필요"),   // 특정 커뮤니티·역사 맥락이 있는 표현
    TIMING_SENSITIVE("시점 확인 필요"),   // 최근 이슈와 맞물릴 수 있는 주제
    HATE_SPEECH("혐오 표현"),
    DISCRIMINATION("차별적 발언"),
    PROFANITY("욕설/비속어"),
    SEXUAL("선정적 내용"),
    VIOLENCE("폭력적 내용"),
    MISINFORMATION("사실관계 논란"),
    FACT_ERROR("사실과 다름"),              // 기사와 대조해 어긋나는 것으로 확인됨
    UNVERIFIED_CLAIM("근거 확인 필요"),      // 뒷받침할 자료를 찾지 못함
    PRIVACY("개인정보 노출"),
    ADVERTISING("광고/협찬 미표기"),

    // 유튜브 수익화 (광고주 친화 가이드라인)
    AD_DEMONETIZED("광고 수익 없음"),
    AD_LIMITED("광고 제한 (노란 딱지)"),

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

    /**
     * 아는 값만 돌려준다. 모르면 null.
     *
     * fromOrDefault 는 모델이 엉뚱한 유형을 보냈을 때 조용히 기본값으로 바꾼다.
     * 그 기본값이 허용 목록 안에 있으면 검사를 그대로 통과한다.
     * "유형은 이 셋만" 같은 관문을 세워둔 곳에서는 이 메서드를 써야 한다.
     */
    public static RiskCategory from(String value) {
        return fromOrDefault(value, null);
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
