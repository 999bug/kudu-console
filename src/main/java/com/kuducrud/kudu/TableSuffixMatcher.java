package com.kuducrud.kudu;

import java.util.ArrayList;
import java.util.List;

/**
 * 「按后缀删表」的匹配规则。
 * <p>后缀只跟表名的结尾有关：{@code impala::} 前缀在名字开头，剥离与否不影响结尾，
 * 所以匹配只看完整表名（忽略大小写）。{@code sys.*} / {@code kudu.*} 系统表永远不参与删除。
 */
public final class TableSuffixMatcher {

    private TableSuffixMatcher() {
    }

    /** 去掉首尾空白；null 视为空串。 */
    public static String normalize(String suffix) {
        return suffix == null ? "" : suffix.trim();
    }

    /** 完整表名以 suffix 结尾即算匹配（忽略大小写）。 */
    public static boolean matches(String suffix, String table) {
        String normalizedSuffix = normalize(suffix);
        if (normalizedSuffix.isEmpty() || table == null || table.isEmpty()) {
            return false;
        }
        return endsWithIgnoreCase(table, normalizedSuffix);
    }

    /** 命中后缀且可安全删除的表（排除系统表），按表名忽略大小写排序。 */
    public static List<String> select(List<String> tables, String suffix) {
        List<String> result = new ArrayList<>();
        if (tables == null) {
            return result;
        }
        for (String table : tables) {
            if (matches(suffix, table) && !TableNameUtil.isSystemTable(table)) {
                result.add(table);
            }
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    /** 命中后缀但因为是系统表被跳过的表。 */
    public static List<String> systemMatched(List<String> tables, String suffix) {
        List<String> result = new ArrayList<>();
        if (tables == null) {
            return result;
        }
        for (String table : tables) {
            if (matches(suffix, table) && TableNameUtil.isSystemTable(table)) {
                result.add(table);
            }
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    /**
     * 两个后缀是否有交集（一方是另一方的后缀）。用于判断运行中的批量任务是否会和本次删除撞车：
     * 例如 {@code _bak} 与 {@code t1_bak} 都会命中表 {@code xx_t1_bak}，算相交；宁可多报冲突也不漏。
     */
    public static boolean overlaps(String a, String b) {
        String left = normalize(a);
        String right = normalize(b);
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        return endsWithIgnoreCase(left, right) || endsWithIgnoreCase(right, left);
    }

    private static boolean endsWithIgnoreCase(String value, String suffix) {
        return value.regionMatches(true, value.length() - suffix.length(), suffix, 0, suffix.length());
    }
}
