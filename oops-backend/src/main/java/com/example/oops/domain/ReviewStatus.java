package com.example.oops.domain;

/**
 * 사용자의 검수 진행 상태. 명세 §1.
 *
 * 분석 상태(AnalysisStatus)와 다릅니다.
 * 분석은 끝났어도 사람이 아직 안 봤을 수 있고, 그 둘은 화면에서 다르게 표시됩니다.
 */
public enum ReviewStatus {

    /** 아직 아무 후보도 결정하지 않았다 */
    NOT_STARTED,

    /** 결정을 시작했지만 남은 후보가 있다 */
    IN_REVIEW,

    /** 모든 후보를 결정하고 검수를 마쳤다 */
    COMPLETED
}
