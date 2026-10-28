package com.kuducrud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 一个 Kudu 平台（集群）的连接配置。
 * <p>超时/webUi 字段为空时回落到 application.yml 里的全局默认值。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClusterConfig(
        String id,
        String name,
        String masters,
        Integer operationTimeoutMs,
        Integer adminTimeoutMs,
        Boolean webUiEnabled,
        Integer webUiPortOffset
) {

    public int operationTimeout(int fallback) {
        return operationTimeoutMs == null || operationTimeoutMs <= 0 ? fallback : operationTimeoutMs;
    }

    public int adminTimeout(int fallback) {
        return adminTimeoutMs == null || adminTimeoutMs <= 0 ? fallback : adminTimeoutMs;
    }

    public boolean webUi(boolean fallback) {
        return webUiEnabled == null ? fallback : webUiEnabled;
    }

    public int webUiPortOffset(int fallback) {
        return webUiPortOffset == null || webUiPortOffset < 0 ? fallback : webUiPortOffset;
    }

    public ClusterConfig withId(String newId) {
        return new ClusterConfig(newId, name, masters, operationTimeoutMs, adminTimeoutMs,
                webUiEnabled, webUiPortOffset);
    }
}
