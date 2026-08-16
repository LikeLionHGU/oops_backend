package com.example.oops.domain;

/** 제작자가 검토 후보를 어떻게 처리했는지. 명세 §9-2. */
public enum ReviewActionType {

    /** 봤고 그대로 두기로 했다 */
    CONFIRMED,

    /** 이 지적을 보고 실제로 편집했다 */
    EDITED,

    /** 판단을 미뤘다 */
    HOLD,

    /** 도움이 안 되는 지적이었다 (오탐) */
    NOT_USEFUL
}
