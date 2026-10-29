package com.kuducrud.kudu;

import java.util.ArrayList;
import java.util.List;

/**
 * 「按前缀删表」的匹配规则。
 * <p>Kudu 没有 catalog，表名前缀就是事实上的命名空间。匹配时同时看完整表名和剥离
 * {@code impala::} 之后的表名（忽略大小写），因此前缀 {@code db.} 既能命中 {@code db.t1}，
 * 也能命中 {@code impala::db.t2}；{@code sys.*} / {@code kudu.*} 系统表永远不参与删除。
 */
public final class TablePrefixMatcher {

    private TablePrefixMatcher() {
    }

    /** 去掉首尾空白；null 视为空串。 */
    public static String normalize(String prefix) {
        return prefix == null ? "" : prefix.trim();
    }

    /** 完整表名或剥离 impala:: 后的表名，任一以 prefix 开头即算匹配（忽略大小写）。 */
    public static boolean matches(String prefix, String table) {
        String normalizedPrefix = normalize(prefix);
        if (normalizedPrefix.isEmpty() || table == null || table.isEmpty()) {
            return false;
        }
        return startsWithIgnoreCase(table, normalizedPrefix)
                || startsWithIgnoreCase(TableNameUtil.stripImpalaPrefix(table), normalizedPrefix);
    }

    /** 命中前缀且可安全删除的表（排除系统表），按表名忽略大小写排序。 */
    public static List<String> select(List<String> tables, String prefix) {
        List<String> result = new ArrayList<>();
        if (tables == null) {
            return result;
        }
        for (String table : tables) {
            if (matches(prefix, table) && !TableNameUtil.isSystemTable(table)) {
                result.add(table);
            }
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    /** 命中前缀但因为是系统表被跳过的表。 */
    public static List<String> systemMatched(List<String> tables, String prefix) {
        List<String> result = new ArrayList<>();
        if (tables == null) {
            return result;
        }
        for (String table : tables) {
            if (matches(prefix, table) && TableNameUtil.isSystemTable(table)) {
                result.add(table);
            }
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    /**
     * 两个前缀是否有交集（一个包含另一个）。用于判断运行中的批量任务是否会和本次删除撞车；
     * 比较时同样忽略 impala:: 前缀，宁可多报冲突也不漏。
     */
    public static boolean overlaps(String a, String b) {
        String left = normalize(a);
        String right = normalize(b);
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        String leftStripped = TableNameUtil.stripImpalaPrefix(left);
        String rightStripped = TableNameUtil.stripImpalaPrefix(right);
        return oneContains(left, right)
                || oneContains(leftStripped, right)
                || oneContains(left, rightStripped)
                || oneContains(leftStripped, rightStripped);
    }

    private static boolean oneContains(String a, String b) {
        return startsWithIgnoreCase(a, b) || startsWithIgnoreCase(b, a);
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
