package com.kuducrud.web;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态与错误码，由 GlobalExceptionHandler 转成统一错误体。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public ApiException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "TABLE_NOT_FOUND", message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException unsupported(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED", message);
    }

    public static ApiException unavailable(String message, Throwable cause) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "KUDU_UNAVAILABLE", message, cause);
    }
}
