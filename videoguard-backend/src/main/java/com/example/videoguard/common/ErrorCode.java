package com.example.videoguard.common;

import org.springframework.http.HttpStatus;

/** API 명세 1-6 의 에러 코드. 프론트가 이 문자열로 분기한다. */
public enum ErrorCode {

    VIDEO_NOT_FOUND(HttpStatus.NOT_FOUND, "영상을 찾을 수 없습니다."),
    FRAME_NOT_FOUND(HttpStatus.NOT_FOUND, "프레임을 찾을 수 없습니다."),
    UNSUPPORTED_VIDEO_FORMAT(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 영상 형식입니다."),
    MAX_UPLOAD_SIZE_EXCEEDED(HttpStatus.PAYLOAD_TOO_LARGE, "업로드 가능한 파일 크기를 초과했습니다."),
    ANALYSIS_IN_PROGRESS(HttpStatus.CONFLICT, "이미 분석이 진행 중입니다."),
    ANALYSIS_NOT_COMPLETED(HttpStatus.CONFLICT, "분석이 아직 완료되지 않았습니다."),
    INVALID_ANALYSIS_STATE(HttpStatus.BAD_REQUEST, "현재 분석 상태에서는 처리할 수 없는 요청입니다."),
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
