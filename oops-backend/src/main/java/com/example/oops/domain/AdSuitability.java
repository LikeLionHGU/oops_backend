package com.example.oops.domain;

/**
 * 유튜브 광고 적합성 등급.
 * 광고주 친화적인 콘텐츠 가이드라인의 3단계를 그대로 따른다.
 * https://support.google.com/youtube/answer/6162278
 */
public enum AdSuitability {

    MONETIZED("광고 수익 가능", "가이드라인 위반 요소가 발견되지 않았습니다."),
    LIMITED("광고 수익 제한 (노란 딱지)", "광고가 일부만 붙거나 단가가 크게 떨어집니다."),
    DEMONETIZED("광고 수익 없음", "광고가 아예 붙지 않습니다.");

    private final String label;
    private final String note;

    AdSuitability(String label, String note) {
        this.label = label;
        this.note = note;
    }

    public String getLabel() {
        return label;
    }

    public String getNote() {
        return note;
    }

    /** 더 나쁜 등급을 고른다. 영상 전체 등급은 가장 심한 구간을 따른다. */
    public AdSuitability worse(AdSuitability other) {
        if (other == null) return this;
        return this.ordinal() >= other.ordinal() ? this : other;
    }

    public static AdSuitability fromOrDefault(String value, AdSuitability fallback) {
        if (value == null || value.isBlank()) return fallback;
        for (AdSuitability level : values()) {
            if (level.name().equalsIgnoreCase(value.trim())) return level;
        }
        return fallback;
    }
}
