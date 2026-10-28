package com.kuducrud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 异步任务视图（当前只有 truncate）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobView(
        String id,
        String type,
        String cluster,
        String table,
        String state,
        long processed,
        long deleted,
        long failed,
        Long totalEstimate,
        long elapsedMs,
        Double ratePerSec,
        String message,
        boolean cancelable,
        String startedAt,
        String finishedAt
) {
}
