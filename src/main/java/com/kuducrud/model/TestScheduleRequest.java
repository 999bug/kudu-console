package com.kuducrud.model;

/**
 * 新建 / 编辑定时任务的请求。
 * <p>{@code enabled} 为空时：新建默认启用，编辑保持原状态。
 */
public record TestScheduleRequest(
        String name,
        Boolean enabled,
        Integer intervalSeconds,
        Integer maxRuns,
        Long endAtMs,
        java.util.List<String> tables,
        TestActionSpec actions
) {
}
