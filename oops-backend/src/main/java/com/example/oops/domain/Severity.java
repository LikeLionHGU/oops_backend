package com.example.oops.domain;

public enum Severity {
    LOW,
    MEDIUM,
    HIGH;

    /** 0.0 ~ 1.0 점수를 등급으로 환산 */
    public static Severity fromScore(double score) {
        if (score >= 0.7) return HIGH;
        if (score >= 0.4) return MEDIUM;
        return LOW;
    }

    public int weight() {
        return switch (this) {
            case HIGH -> 3;
            case MEDIUM -> 2;
            case LOW -> 1;
        };
    }
}
