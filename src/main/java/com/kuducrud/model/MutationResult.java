package com.kuducrud.model;

import java.util.List;

/**
 * 行写入结果：逐行结果 + 行错误。
 */
public record MutationResult(
        String op,
        int requested,
        int applied,
        int failed,
        List<RowError> errors
) {

    public record RowError(int rowIndex, String rowKey, String message) {
    }
}
