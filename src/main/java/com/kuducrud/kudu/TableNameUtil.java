package com.kuducrud.kudu;

/**
 * Kudu 表名解析。
 * <p>Kudu 没有库（catalog）概念，{@code db} 只是表名的一部分：
 * 先剥离 Impala 前缀 {@code impala::}，再取第一个 {@code .} 之前的部分；
 * 没有点则归入 {@code default}。写操作必须使用完整表名。
 */
public final class TableNameUtil {

    public static final String DEFAULT_DB = "default";
    public static final String IMPALA_PREFIX = "impala::";

    private TableNameUtil() {
    }

    public static String stripImpalaPrefix(String table) {
        if (table == null) {
            return null;
        }
        return table.regionMatches(true, 0, IMPALA_PREFIX, 0, IMPALA_PREFIX.length())
                ? table.substring(IMPALA_PREFIX.length())
                : table;
    }

    public static boolean isImpalaManaged(String table) {
        return table != null && table.regionMatches(true, 0, IMPALA_PREFIX, 0, IMPALA_PREFIX.length());
    }

    public static String dbOf(String table) {
        if (table == null || table.isBlank()) {
            return DEFAULT_DB;
        }
        String normalized = stripImpalaPrefix(table);
        int idx = normalized.indexOf('.');
        return idx > 0 ? normalized.substring(0, idx) : DEFAULT_DB;
    }

    public static String shortNameOf(String table) {
        if (table == null || table.isBlank()) {
            return "";
        }
        String normalized = stripImpalaPrefix(table);
        int idx = normalized.lastIndexOf('.');
        return idx >= 0 && idx < normalized.length() - 1 ? normalized.substring(idx + 1) : normalized;
    }

    /** Kudu 内置系统表（sys.* / kudu.*），控制台一律禁止写入类操作。 */
    public static boolean isSystemTable(String table) {
        String db = dbOf(table);
        return "sys".equalsIgnoreCase(db) || "kudu".equalsIgnoreCase(db);
    }
}
