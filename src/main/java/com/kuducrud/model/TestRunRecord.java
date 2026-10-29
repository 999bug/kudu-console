package com.kuducrud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** 定时任务的一次执行记录（内存保留最近 20 条）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TestRunRecord(
        String id,
        String scheduleId,
        String trigger,
        long startedAtMs,
        long finishedAtMs,
        String state,
        String message,
        List<TestTableResult> tables
) {
}
