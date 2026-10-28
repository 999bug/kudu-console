package com.kuducrud.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 统一错误响应体。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, Map<String, Object> details) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null);
    }

    public static ApiError fields(String message, List<Map<String, Object>> fields) {
        return new ApiError("VALIDATION_FAILED", message, Map.of("fields", fields));
    }
}
