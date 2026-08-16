package com.example.oops.domain;

/**
 * 분석 수행 여부를 사용자에게 보고하는 단위. 명세 §19-5.
 *
 * 내부 분석기는 8개인데 여기는 7개다. 일부러 다르다.
 * 사용자가 알아야 하는 건 "발언을 봤는가" 이지
 * "룰 분석기와 LLM 분석기가 각각 돌았는가" 가 아니다.
 */
public enum CoverageStep {

    STT("음성 인식"),
    OCR("화면 글자 인식"),
    SPEECH_REVIEW("발언 검토"),
    SCREEN_TEXT_REVIEW("화면 글자 검토"),
    FACT_ENTITY("이름·수치 확인"),
    CONTEXT_REFERENCE("맥락 참고"),
    VISUAL("화면 자료 확인");

    private final String label;

    CoverageStep(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 분석기 키를 보고 단위로 묶는다.
     *
     * subtitle 과 speech-review 는 둘 다 발언을 본다.
     * 앞은 룰 기반 안전망이고 뒤는 LLM 인데, 사용자에게는 같은 "발언 검토" 다.
     * 여기 없는 분석기(caption-mismatch, monetization 등)는 보고 대상이 아니다.
     */
    public static CoverageStep of(String analyzerKey) {
        return switch (analyzerKey) {
            case "subtitle", "speech-review" -> SPEECH_REVIEW;
            case "screen-text", "screen-text-review" -> SCREEN_TEXT_REVIEW;
            case "entity-check" -> FACT_ENTITY;
            case "context-check" -> CONTEXT_REFERENCE;
            default -> null;
        };
    }
}
