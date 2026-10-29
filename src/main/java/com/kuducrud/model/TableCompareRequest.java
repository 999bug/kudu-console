package com.kuducrud.model;

/**
 * 表比对请求。集群为空时用当前激活平台；左右可以选不同平台，用于比对恢复前后的两张表。
 */
public record TableCompareRequest(
        String leftCluster,
        String leftTable,
        String rightCluster,
        String rightTable,
        Boolean compareData,
        Integer maxRows,
        Integer sampleLimit
) {
}
