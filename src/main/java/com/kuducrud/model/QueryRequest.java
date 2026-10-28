package com.kuducrud.model;

import java.util.List;

/**
 * 查询请求。条件之间固定为 AND（Kudu 不支持 OR）。
 */
public record QueryRequest(
        List<String> columns,
        List<FilterSpec> filters,
        Integer limit,
        Integer offset
) {
}
