package com.example.oops.common;

/**
 * 응답 공통 껍데기. 명세 §1.
 *
 * 성공이든 실패든 세 필드가 항상 있습니다.
 *   { success, data, error }
 *
 * 성공이면 error 가 null, 실패면 data 가 null 입니다.
 * 프론트는 success 만 보고 분기하면 되고, 실패 시에는 error.code 로 문구를 정합니다.
 *
 * 예전에는 최상위에 message 를 뒀는데 없앴습니다.
 * 성공 문구는 화면에서 쓰지 않고, 실패 문구는 error 안에 있어야 프론트가
 * "코드로 분기하고 문구는 우리가 정한다" 를 지킬 수 있기 때문입니다.
 */
public record ApiResponse<T>(boolean success, T data, ApiError error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null);
    }

    public static <T> ApiResponse<T> fail(ApiError error) {
        return new ApiResponse<>(false, null, error);
    }
}
