package com.kuducrud.model;

/**
 * 表比对请求，支持三种模式：
 * <ul>
 *   <li>{@code table}（默认）：单表比对，需要 {@code leftTable} / {@code rightTable}；</li>
 *   <li>{@code database}：按库比对，需要 {@code leftDb} / {@code rightDb}，两库内按表短名逐一配对；</li>
 *   <li>{@code prefix}：按前缀比对，需要 {@code leftDb} / {@code prefix}，目标库自动取 {@code prefix + leftDb}
 *       （如源库 {@code t_1028_}、前缀 {@code rec_} → 目标库 {@code rec_t_1028_}）。</li>
 * </ul>
 * 集群为空时用当前激活平台；左右可以选不同平台，用于比对恢复前后的数据。
 */
public record TableCompareRequest(
        String mode,
        String leftCluster,
        String leftTable,
        String rightCluster,
        String rightTable,
        String leftDb,
        String rightDb,
        String prefix,
        Boolean compareData,
        Integer maxRows,
        Integer sampleLimit
) {
}
