package com.kuducrud.model;

import java.util.List;
import java.util.Map;

/**
 * 建表请求。
 */
public record CreateTableRequest(
        String name,
        Integer numReplicas,
        String owner,
        String comment,
        List<ColumnDef> columns,
        List<PartitionDef.HashPartition> hashPartitions,
        List<String> rangePartitionColumns,
        List<PartitionDef.RangeBound> rangePartitions,
        Map<String, String> extraConfigs
) {
}
