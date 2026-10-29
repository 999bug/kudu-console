package com.kuducrud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 两张表的比对结果。结构不一致时不做数据比对（{@code data} 为空）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TableCompareResult(
        boolean consistent,
        String message,
        TableSide left,
        TableSide right,
        boolean schemaEqual,
        List<String> leftSchema,
        List<String> rightSchema,
        List<ColumnDiff> columnDiffs,
        boolean primaryKeyEqual,
        List<String> leftPrimaryKey,
        List<String> rightPrimaryKey,
        DataDiff data,
        long elapsedMs
) {

    public record TableSide(
            String clusterId,
            String clusterName,
            String table,
            boolean exists,
            int columns,
            Long rows
    ) {
    }

    /** kind: MISSING_IN_RIGHT / EXTRA_IN_RIGHT / CHANGED */
    public record ColumnDiff(String kind, String name, String left, String right) {
    }

    public record DataDiff(
            long matched,
            long missing,
            long extra,
            long mismatch,
            List<DiffSample> missingSamples,
            List<DiffSample> extraSamples,
            List<DiffSample> mismatchSamples
    ) {
    }

    /** leftRow 为空表示目标多出，rightRow 为空表示目标缺失。 */
    public record DiffSample(String key, String leftRow, String rightRow) {
    }
}
