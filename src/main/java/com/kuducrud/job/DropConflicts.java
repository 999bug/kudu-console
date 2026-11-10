package com.kuducrud.job;

import com.kuducrud.kudu.TableNameUtil;
import com.kuducrud.kudu.TablePrefixMatcher;
import com.kuducrud.kudu.TableSuffixMatcher;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 批量删除前的冲突检测：同一张表、同一个库或有交集的前缀/后缀上已有破坏性任务时拒绝提交，
 * 避免两个任务同时 deleteTable 同一批表。
 */
final class DropConflicts {

    private DropConflicts() {
    }

    /** 按前缀删表：表级任务命中任一目标表、库级任务覆盖该前缀、前缀/后缀任务与之相交都算冲突。 */
    static String forPrefix(JobRegistry registry, String clusterId, String prefix, List<String> tables) {
        Set<String> targets = new HashSet<>(tables);
        for (String key : registry.runningResourceKeys(clusterId)) {
            if (key.startsWith(JobRegistry.TABLE_KEY)) {
                String table = key.substring(JobRegistry.TABLE_KEY.length());
                if (targets.contains(table) || TablePrefixMatcher.matches(prefix, table)) {
                    return "表 " + table + " 正在执行破坏性任务，请等待其结束";
                }
            } else if (key.startsWith(JobRegistry.DB_KEY)) {
                String db = key.substring(JobRegistry.DB_KEY.length());
                if (TablePrefixMatcher.overlaps(prefix, db + ".")) {
                    return "库 " + db + " 正在执行删除任务，请等待其结束";
                }
            } else if (key.startsWith(JobRegistry.PREFIX_KEY)) {
                String other = key.substring(JobRegistry.PREFIX_KEY.length());
                if (TablePrefixMatcher.overlaps(prefix, other)) {
                    return "前缀 " + other + " 正在执行删除任务，请等待其结束";
                }
            } else if (key.startsWith(JobRegistry.SUFFIX_KEY)) {
                String suffix = key.substring(JobRegistry.SUFFIX_KEY.length());
                if (anyMatchesSuffix(tables, suffix)) {
                    return "后缀 " + suffix + " 正在执行删除任务，请等待其结束";
                }
            }
        }
        return null;
    }

    /** 按后缀删表：表级任务命中任一目标表、库级/前缀任务覆盖目标表、后缀任务与之相交都算冲突。 */
    static String forSuffix(JobRegistry registry, String clusterId, String suffix, List<String> tables) {
        Set<String> targets = new HashSet<>(tables);
        for (String key : registry.runningResourceKeys(clusterId)) {
            if (key.startsWith(JobRegistry.TABLE_KEY)) {
                String table = key.substring(JobRegistry.TABLE_KEY.length());
                if (targets.contains(table) || TableSuffixMatcher.matches(suffix, table)) {
                    return "表 " + table + " 正在执行破坏性任务，请等待其结束";
                }
            } else if (key.startsWith(JobRegistry.DB_KEY)) {
                String db = key.substring(JobRegistry.DB_KEY.length());
                if (anyInDb(tables, db)) {
                    return "库 " + db + " 正在执行删除任务，请等待其结束";
                }
            } else if (key.startsWith(JobRegistry.PREFIX_KEY)) {
                String prefix = key.substring(JobRegistry.PREFIX_KEY.length());
                if (anyMatchesPrefix(tables, prefix)) {
                    return "前缀 " + prefix + " 正在执行删除任务，请等待其结束";
                }
            } else if (key.startsWith(JobRegistry.SUFFIX_KEY)) {
                String other = key.substring(JobRegistry.SUFFIX_KEY.length());
                if (TableSuffixMatcher.overlaps(suffix, other)) {
                    return "后缀 " + other + " 正在执行删除任务，请等待其结束";
                }
            }
        }
        return null;
    }

    /** 删库：表级任务命中该库、或前缀/后缀任务覆盖该库下的表都算冲突。 */
    static String forDb(JobRegistry registry, String clusterId, String db, List<String> tables) {
        for (String key : registry.runningResourceKeys(clusterId)) {
            if (key.startsWith(JobRegistry.TABLE_KEY)) {
                String table = key.substring(JobRegistry.TABLE_KEY.length());
                if (db.equalsIgnoreCase(TableNameUtil.dbOf(table))) {
                    return "库 " + db + " 下有表正在执行破坏性任务，请等待其结束";
                }
            } else if (key.startsWith(JobRegistry.PREFIX_KEY)) {
                String prefix = key.substring(JobRegistry.PREFIX_KEY.length());
                if (TablePrefixMatcher.overlaps(prefix, db + ".")) {
                    return "前缀 " + prefix + " 的任务会删除库 " + db + " 下的表，请等待其结束";
                }
            } else if (key.startsWith(JobRegistry.SUFFIX_KEY)) {
                String suffix = key.substring(JobRegistry.SUFFIX_KEY.length());
                if (anyMatchesSuffix(tables, suffix)) {
                    return "后缀 " + suffix + " 的任务会删除库 " + db + " 下的表，请等待其结束";
                }
            }
        }
        return null;
    }

    private static boolean anyInDb(List<String> tables, String db) {
        for (String table : tables) {
            if (db.equalsIgnoreCase(TableNameUtil.dbOf(table))) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyMatchesPrefix(List<String> tables, String prefix) {
        for (String table : tables) {
            if (TablePrefixMatcher.matches(prefix, table)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyMatchesSuffix(List<String> tables, String suffix) {
        for (String table : tables) {
            if (TableSuffixMatcher.matches(suffix, table)) {
                return true;
            }
        }
        return false;
    }
}
