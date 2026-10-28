package com.kuducrud.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 表分区信息：hash 桶 + range 列/边界。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PartitionDef(
        List<HashPartition> hashPartitions,
        List<String> rangeColumns,
        List<RangeBound> rangeBounds,
        Integer estimatedTablets
) {

    public record HashPartition(List<String> columns, int buckets, int seed) {
    }

    /** null 表示该侧无界。 */
    public record RangeBound(Map<String, JsonNode> lower, Map<String, JsonNode> upper) {
    }
}
