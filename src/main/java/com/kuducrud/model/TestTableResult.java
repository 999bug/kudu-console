package com.kuducrud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 单张表的备份测试结果。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TestTableResult(
        String table,
        int deleted,
        int added,
        String column,
        int failed,
        String message
) {
}
