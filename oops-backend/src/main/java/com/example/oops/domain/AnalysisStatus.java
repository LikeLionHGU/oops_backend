package com.example.oops.domain;

/** API 명세 1-8. 영상과 분석 잡이 같은 값을 공유한다. */
public enum AnalysisStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,

    /** 사용자가 분석을 취소했다. 재시도할 수 있다 */
    CANCELLED;

    public boolean isRunning() {
        return this == PENDING || this == PROCESSING;
    }

    /** 재시도할 수 있는 상태인지. 명세 §7 — 실패와 취소만 재시도한다 */
    public boolean isRetryable() {
        return this == FAILED || this == CANCELLED;
    }
}
