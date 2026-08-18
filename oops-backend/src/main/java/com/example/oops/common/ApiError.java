package com.example.oops.common;

/**
 * 에러 본문. 명세 §1.
 *
 * code 는 프론트가 분기할 고정 문자열이고,
 * message 는 개발 확인용 기본 문구입니다. 화면 문구의 기준으로 쓰지 않습니다.
 * details 에는 추적용 값(traceId 등)을 담습니다.
 */
public record ApiError(String code, String message, Object details) {

    public static ApiError of(ErrorCode errorCode, String message, String traceId) {
        return new ApiError(
                errorCode.name(),
                message == null || message.isBlank() ? errorCode.getDefaultMessage() : message,
                traceId == null ? null : new Details(traceId));
    }

    /** 서버 로그에서 같은 요청을 찾기 위한 값 */
    public record Details(String traceId) {}
}
