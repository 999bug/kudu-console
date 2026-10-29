package com.kuducrud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 按库 / 按前缀的批量比对结果：库内所有表按短名逐一配对后的汇总。
 * <p>只输出每张表的结论与差异数，不含行级样本；需要样本时对单张表再发起单表比对。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchCompareResult(
        String mode,
        String leftCluster,
        String leftClusterName,
        String rightCluster,
        String rightClusterName,
        String leftDb,
        String rightDb,
        String prefix,
        boolean allConsistent,
        String message,
        int totalTables,
        int consistentTables,
        int inconsistentTables,
        int failedTables,
        List<Item> items,
        long elapsedMs
) {

    /** state: CONSISTENT / SCHEMA_DIFF / DATA_DIFF / MISSING_IN_RIGHT / MISSING_IN_LEFT / FAILED */
    public record Item(
            String leftTable,
            String rightTable,
            String state,
            String message,
            Boolean schemaEqual,
            Long leftRows,
            Long rightRows,
            Long missing,
            Long extra,
            Long mismatch,
            long elapsedMs
    ) {
    }
}
