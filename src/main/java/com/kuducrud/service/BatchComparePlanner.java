package com.kuducrud.service;

import com.kuducrud.kudu.TableNameUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 批量比对的配对规划：把两个库的表清单按表短名（{@code db} 之后的最后一节）配对，
 * 配对范围是两侧短名的并集——只在一侧存在的表也会成对出现（另一侧为 null），
 * 用于报告"恢复库缺表 / 多表"。
 */
public final class BatchComparePlanner {

    private BatchComparePlanner() {
    }

    /**
     * @param leftTable  源库中的完整表名，源库没有该表时为 {@code null}
     * @param rightTable 目标库中的完整表名，目标库没有该表时为 {@code null}
     */
    public record Pair(String shortName, String leftTable, String rightTable) {
    }

    /** 筛出属于指定库的表（忽略 impala:: 前缀与大小写），排除系统表，按表名排序。 */
    public static List<String> tablesOfDb(List<String> tables, String db) {
        List<String> result = new ArrayList<>();
        for (String table : tables) {
            if (TableNameUtil.isSystemTable(table)) {
                continue;
            }
            if (TableNameUtil.dbOf(table).equalsIgnoreCase(db)) {
                result.add(table);
            }
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    /** 按表短名配对，短名排序输出；同名表（忽略大小写）只保留先出现的一个。 */
    public static List<Pair> plan(List<String> leftTables, List<String> rightTables) {
        Map<String, String> leftByShort = byShortName(leftTables);
        Map<String, String> rightByShort = byShortName(rightTables);
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(leftByShort.keySet());
        names.addAll(rightByShort.keySet());
        List<Pair> pairs = new ArrayList<>();
        for (String name : names) {
            pairs.add(new Pair(name, leftByShort.get(name), rightByShort.get(name)));
        }
        return pairs;
    }

    private static Map<String, String> byShortName(List<String> tables) {
        Map<String, String> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String table : tables) {
            map.putIfAbsent(TableNameUtil.shortNameOf(table), table);
        }
        return map;
    }
}
