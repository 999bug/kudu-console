package com.kuducrud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 备份测试定时任务。时间字段统一用 epoch 毫秒，前端负责本地化展示。
 * <p>{@code lastColumns} 记录每张表上一次自动添加的测试列，REPLACE 模式下轮次开始时先删旧列再加新列。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TestSchedule(
        String id,
        String name,
        String clusterId,
        boolean enabled,
        int intervalSeconds,
        Integer maxRuns,
        Long endAtMs,
        List<String> tables,
        TestActionSpec actions,
        int runCount,
        Long nextRunAtMs,
        Long lastRunAtMs,
        String lastState,
        String lastMessage,
        Map<String, String> lastColumns,
        long createdAtMs,
        long updatedAtMs
) {
}
