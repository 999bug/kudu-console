package com.kuducrud.kudu;

import com.kuducrud.web.ApiException;
import org.apache.kudu.client.KuduException;
import org.springframework.http.HttpStatus;

/**
 * 把 Kudu 异常翻译成带错误码的业务异常。
 * <p>状态判断基于 {@code KuduException.Status#name()}，避免绑定具体客户端版本的枚举常量。
 */
public final class KuduErrors {

    private KuduErrors() {
    }

    /**
     * @return 可抛出的 ApiException；无法识别时返回 null，交给上层按 INTERNAL 处理
     */
    public static ApiException translate(Throwable t, String context) {
        if (t == null) {
            return null;
        }
        if (t instanceof ApiException api) {
            return api;
        }
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof ApiException api) {
                return api;
            }
            if (cause instanceof KuduException ke) {
                return fromKudu(ke, context);
            }
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", context + "：操作被中断");
            }
            if (cause instanceof java.util.concurrent.TimeoutException) {
                return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "KUDU_TIMEOUT", context + "：等待超时");
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        String msg = t.getMessage();
        if (msg != null && msg.toLowerCase().contains("not found")) {
            return ApiException.notFound(context + "：表不存在（" + msg + "）");
        }
        return null;
    }

    private static ApiException fromKudu(KuduException e, String context) {
        String detail = e.getMessage() == null ? e.toString() : e.getMessage();
        String message = context + "：" + detail;
        org.apache.kudu.client.Status status = e.getStatus();
        if (status == null) {
            return new ApiException(HttpStatus.BAD_GATEWAY, "KUDU_ERROR", message, e);
        }
        if (status.isNotFound()) {
            return new ApiException(HttpStatus.NOT_FOUND, "TABLE_NOT_FOUND", message, e);
        }
        if (status.isAlreadyPresent()) {
            return new ApiException(HttpStatus.CONFLICT, "TABLE_EXISTS", message, e);
        }
        if (status.isTimedOut()) {
            return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "KUDU_TIMEOUT", message, e);
        }
        if (status.isInvalidArgument() || status.isIllegalState()) {
            return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, e);
        }
        if (status.isNotSupported() || status.isImmutable() || status.isConfigurationError()) {
            return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED", message, e);
        }
        if (status.isNotAuthorized()) {
            return new ApiException(HttpStatus.FORBIDDEN, "KUDU_FORBIDDEN", message, e);
        }
        if (status.isServiceUnavailable() || status.isNetworkError() || status.isUninitialized()
                || status.isAborted() || status.isIncomplete()) {
            return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "KUDU_UNAVAILABLE", message, e);
        }
        return new ApiException(HttpStatus.BAD_GATEWAY, "KUDU_ERROR", message, e);
    }
}
