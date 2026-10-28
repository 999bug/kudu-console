package com.kuducrud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 表详情：列、主键、副本、分区、统计。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TableDetail(
        String name,
        String shortName,
        String db,
        boolean impalaManaged,
        boolean systemTable,
        String tableId,
        int numReplicas,
        String owner,
        String comment,
        List<String> primaryKey,
        List<ColumnDef> columns,
        PartitionDef partition,
        TableStats stats
) {
}
