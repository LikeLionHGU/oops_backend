package com.example.oops.domain;

/**
 * API 명세 6. 프론트가 이 값으로 카드 UI 를 분기한다.
 * GESTURE, SYMBOL 은 확장 예정.
 */
public enum TimelineEventType {
    SPEECH,   // 발언에서 잡힌 리스크
    CAPTION   // 화면 자막에서 잡힌 리스크 (발언과 불일치 포함)
}
