package com.example.videoguard.common;

/**
 * 에러 응답. API 명세 1-4.
 *
 * error.code 는 프론트 분기용 고정 문자열이고,
 * traceId 는 서버 로그에서 같은 요청을 찾기 위한 값이다.
 */
public record ApiErrorResponse(boolean success, String message, ErrorDetail error) {

    public record ErrorDetail(String code, String traceId) {}

    public static ApiErrorResponse of(ErrorCode errorCode, String message, String traceId) {
        return new ApiErrorResponse(false, message, new ErrorDetail(errorCode.name(), traceId));
    }
}
