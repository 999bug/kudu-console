package com.kuducrud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 比对任务视图：PENDING / RUNNING / SUCCEEDED / FAILED / CANCELED。
 * <p>比对是只读的，不占表锁；执行期间应避免写入，否则结果可能自相矛盾。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompareTaskView(
        String id,
        String state,
        String message,
        long leftScanned,
        long rightScanned,
        long startedAtMs,
        Long finishedAtMs,
        TableCompareResult result
) {
}
