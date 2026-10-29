package com.kuducrud.job;

import com.kuducrud.kudu.TableNameUtil;
import com.kuducrud.kudu.TablePrefixMatcher;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 批量删除前的冲突检测：同一张表、同一个库或有交集的前缀上已有破坏性任务时拒绝提交，
 * 避免两个任务同时 deleteTable 同一批表。
 */
final class DropConflicts {

    private DropConflicts() {
    }

    /** 按前缀删表：表级任务命中任一目标表、库级任务覆盖该前缀、前缀任务与之相交都算冲突。 */
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
            }
        }
        return null;
    }

    /** 删库：表级任务命中该库、或前缀任务覆盖该库都算冲突。 */
    static String forDb(JobRegistry registry, String clusterId, String db) {
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
            }
        }
        return null;
    }
}
