package com.kuducrud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 列定义（并用于建表/加列请求）。
 * <p>{@code defaultValue} 用字符串承载，按列类型解析，和前端输入框一一对应。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ColumnDef(
        String name,
        String type,
        boolean key,
        boolean nullable,
        Integer precision,
        Integer scale,
        Integer length,
        String encoding,
        String compression,
        Integer blockSizeBytes,
        String defaultValue,
        String comment
) {
}
