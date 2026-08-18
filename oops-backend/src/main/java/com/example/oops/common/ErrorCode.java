package com.example.oops.common;

import org.springframework.http.HttpStatus;

/** API 명세 1-6 의 에러 코드. 프론트가 이 문자열로 분기한다. */
public enum ErrorCode {

    VIDEO_NOT_FOUND(HttpStatus.NOT_FOUND, "영상을 찾을 수 없습니다."),
    FRAME_NOT_FOUND(HttpStatus.NOT_FOUND, "프레임을 찾을 수 없습니다."),
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "검토 후보를 찾을 수 없습니다."),
    UNSUPPORTED_VIDEO_FORMAT(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 영상 형식입니다."),
    MAX_UPLOAD_SIZE_EXCEEDED(HttpStatus.PAYLOAD_TOO_LARGE, "업로드 가능한 파일 크기를 초과했습니다."),
    // 업로드 직후 길이 검증은 아직 없다. 현재는 분석 중에 파이썬이 길이를 재고 실패로 끝낸다.
    // 명세 §2-1 대로 업로드에서 거절하려면 파이썬에 /probe 를 추가해야 한다.
    MAX_VIDEO_DURATION_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "분석 가능한 영상 길이를 초과했습니다."),
    ANALYSIS_IN_PROGRESS(HttpStatus.CONFLICT, "이미 분석이 진행 중입니다."),
    ANALYSIS_NOT_COMPLETED(HttpStatus.CONFLICT, "분석이 아직 완료되지 않았습니다."),
    // 명세 §1 — 상태 전이가 맞지 않는 요청은 409 다. 값이 틀린 400 과 구분한다.
    INVALID_ANALYSIS_STATE(HttpStatus.CONFLICT, "현재 상태에서 요청을 수행할 수 없습니다."),

    /** 결정하지 않은 검토 후보가 남아 있는데 검수 완료를 요청했다 */
    REVIEW_INCOMPLETE(HttpStatus.CONFLICT, "아직 결정하지 않은 검토 후보가 있습니다."),

    /**
     * 보관 기간이 지나 원본 영상을 이미 지웠다.
     *
     * 404 가 아니라 410 인 이유는, 없었던 게 아니라 있다가 없어진 것이기 때문이다.
     * 프론트는 이 코드를 받으면 "파일을 못 찾았습니다" 가 아니라
     * "보관 기간이 지나 원본은 삭제되었습니다" 를 보여줘야 한다.
     * 검수 결과 자체는 그대로 남아 있으므로 리포트는 계속 열린다.
     */
    VIDEO_SOURCE_PURGED(HttpStatus.GONE,
            "보관 기간이 지나 원본 영상은 삭제되었습니다. 검수 결과는 그대로 확인할 수 있습니다."),

    /** 영상 Range 요청 범위가 잘못됐다 */
    RANGE_NOT_SATISFIABLE(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
            "요청한 재생 구간이 올바르지 않습니다."),
    WORKER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "분석 서버를 사용할 수 없습니다."),
    ANALYSIS_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "분석에 실패했습니다."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
