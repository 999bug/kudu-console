package com.kuducrud.model;

import java.util.List;
import java.util.Map;

/**
 * 查询结果。分页为「扫描 + 跳过 offset」，hasNext 由 limit+1 探测得出。
 */
public record QueryResponse(
        List<ColumnDef> columns,
        List<Map<String, Object>> rows,
        int limit,
        int offset,
        int returned,
        boolean hasNext,
        int scannedRows,
        boolean clientFiltered,
        long elapsedMs,
        boolean truncated
) {
}
