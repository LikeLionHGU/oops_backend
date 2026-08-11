package com.example.videoguard.common;

import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusiness(BusinessException e) {
        return build(e.getErrorCode(), e.getMessage(), e, false);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleUploadSize(MaxUploadSizeExceededException e) {
        return build(ErrorCode.MAX_UPLOAD_SIZE_EXCEEDED,
                ErrorCode.MAX_UPLOAD_SIZE_EXCEEDED.getDefaultMessage(), e, false);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return build(ErrorCode.INVALID_REQUEST, message, e, false);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class,
                       MissingServletRequestParameterException.class})
    public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception e) {
        return build(ErrorCode.INVALID_REQUEST, e.getMessage(), e, false);
    }

    /**
     * 클라이언트가 먼저 연결을 끊은 경우.
     *
     * 영상 재생 중에 사용자가 다른 지점으로 이동하면(seek) 브라우저는
     * 받고 있던 스트림을 끊고 새 Range 요청을 보낸다. 정상 동작이다.
     * 서버 오류가 아니므로 응답을 쓰지 않고 조용히 넘어간다.
     *
     * 여기서 응답을 쓰려고 하면 Content-Type 이 이미 video/mp4 로 정해져 있어서
     * "No converter for ApiErrorResponse" 라는 2차 오류까지 발생한다.
     */
    @ExceptionHandler({AsyncRequestNotUsableException.class, ClientAbortException.class})
    public void handleClientAbort(Exception e) {
        log.debug("클라이언트가 연결을 끊었습니다 (영상 이동 등): {}", e.getMessage());
    }

    /**
     * 없는 정적 파일 요청(favicon.ico 등).
     * 브라우저가 자동으로 보내는 것이라 서버 오류가 아니다.
     * 기본 핸들러에 걸리면 스택트레이스가 통째로 찍혀서 진짜 오류가 묻힌다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException e) {
        log.debug("정적 리소스 없음: {}", e.getResourcePath());
        return ResponseEntity.status(ErrorCode.VIDEO_NOT_FOUND.getStatus())
                .body(ApiErrorResponse.of(ErrorCode.VIDEO_NOT_FOUND,
                        "요청한 경로를 찾을 수 없습니다.", "-"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception e) {
        return build(ErrorCode.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_SERVER_ERROR.getDefaultMessage(), e, true);
    }

    private ResponseEntity<ApiErrorResponse> build(ErrorCode code, String message,
                                                   Exception e, boolean unexpected) {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        if (unexpected) {
            log.error("[{}] 처리되지 않은 예외", traceId, e);
        } else {
            log.warn("[{}] {} - {}", traceId, code.name(), message);
        }

        return ResponseEntity.status(code.getStatus())
                .body(ApiErrorResponse.of(code, message, traceId));
    }
}
