package com.example.oops.domain;

/** 분석 단계 하나의 수행 결과. 명세 §15-2 AnalyzerStatus. */
public enum AnalyzerStatus {

    /** 돌았고 끝까지 마쳤다. 후보가 0건이어도 SUCCESS 다 */
    SUCCESS,

    /** 돌다가 실패했다. 결과를 믿으면 안 된다 */
    FAILED,

    /** 켜져 있지만 조건이 안 맞아 건너뛰었다 (대본 없음, API 키 없음 등) */
    SKIPPED,

    /** 애초에 켜지 않은 기능이다 */
    NOT_ENABLED;

    /**
     * 같은 단계를 여러 분석기가 나눠 맡을 때 어느 상태로 보고할지.
     *
     * 나쁜 쪽을 남긴다. 룰 분석기는 성공했는데 LLM 분석기가 실패했다면
     * 사용자에게는 실패라고 알려야 한다.
     * 성공만 보여주면 "봤는데 없다" 로 읽히는데 실제로는 절반만 본 것이다.
     */
    public AnalyzerStatus worseOf(AnalyzerStatus other) {
        if (other == null) return this;
        return severity() >= other.severity() ? this : other;
    }

    private int severity() {
        return switch (this) {
            case FAILED -> 3;
            case SKIPPED -> 2;
            case SUCCESS -> 1;
            case NOT_ENABLED -> 0;
        };
    }
}
