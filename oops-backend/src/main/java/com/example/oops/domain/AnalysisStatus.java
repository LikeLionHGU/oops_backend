package com.example.oops.domain;

/** API 명세 1-8. 영상과 분석 잡이 같은 값을 공유한다. */
public enum AnalysisStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    public boolean isRunning() {
        return this == PENDING || this == PROCESSING;
    }
}
