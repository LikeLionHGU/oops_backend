package com.example.oops.domain;

/**
 * 영상 유형. 유형에 따라 어떤 분석기를 돌릴지가 달라진다.
 *
 * 경제·정책 해설물은 틀린 숫자 하나가 논란이 되지만,
 * 인터뷰·팟캐스트는 발언이 지금 시점에 어떻게 읽히느냐가 문제가 된다.
 * 같은 잣대로 보면 둘 다 놓친다.
 */
public enum ContentGenre {

    ECONOMY_POLICY("경제·정책·데이터 해설", "수치와 인과 설명이 많아 사실 오류가 치명적"),
    INVESTMENT_FINANCE("투자·주식·금융", "단정적 전망과 미표기 홍보가 문제가 됨"),
    INTERVIEW_PODCAST("인터뷰·팟캐스트", "발언이 공개 시점의 이슈와 맞물려 논란이 됨"),
    GENERAL("일반", "특정 유형으로 분류되지 않음");

    private final String label;
    private final String note;

    ContentGenre(String label, String note) {
        this.label = label;
        this.note = note;
    }

    public String getLabel() {
        return label;
    }

    public String getNote() {
        return note;
    }

    /** 사실 검증이 중요한 유형인지 */
    public boolean needsFactCheck() {
        return this == ECONOMY_POLICY || this == INVESTMENT_FINANCE;
    }

    /** 발언의 시의성이 특히 중요한 유형인지 */
    public boolean needsTimelinessFocus() {
        return this == INTERVIEW_PODCAST;
    }

    public static ContentGenre fromOrDefault(String value, ContentGenre fallback) {
        if (value == null || value.isBlank()) return fallback;
        for (ContentGenre genre : values()) {
            if (genre.name().equalsIgnoreCase(value.trim())) {
                return genre;
            }
        }
        return fallback;
    }
}
