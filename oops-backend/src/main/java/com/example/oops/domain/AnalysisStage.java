package com.example.oops.domain;

/** API 명세 1-9. 프론트 진행률 화면이 이 값으로 단계 라벨을 그린다. */
public enum AnalysisStage {

    UPLOAD("업로드 처리 중"),
    STT("음성 인식 중"),
    TEXT_RISK("발언 리스크 분석 중"),
    SCENE_DETECTION("장면 분석 중"),
    OCR("화면 자막 분석 중"),
    MULTIMODAL("발언·화면 교차 분석 중"),
    FINALIZING("리포트 생성 중"),
    COMPLETED("분석 완료");

    private final String defaultMessage;

    AnalysisStage(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
