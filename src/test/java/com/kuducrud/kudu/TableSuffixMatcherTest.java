package com.kuducrud.kudu;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableSuffixMatcherTest {

    @Test
    void matchesByTableNameEnding() {
        assertTrue(TableSuffixMatcher.matches("_bak", "db.orders_bak"));
        assertTrue(TableSuffixMatcher.matches("_bak", "impala::db.orders_bak"), "impala:: 在开头，不影响结尾匹配");
        assertTrue(TableSuffixMatcher.matches("_BAK", "db.orders_bak"), "后缀匹配忽略大小写");
        assertTrue(TableSuffixMatcher.matches("rs_bak", "impala::db.orders_bak"),
                "剥离 impala:: 后的后缀同样是完整名的后缀");
        assertFalse(TableSuffixMatcher.matches("_bak", "db.orders_bak_2024"), "后缀必须在结尾");
        assertFalse(TableSuffixMatcher.matches("  ", "db.orders"));
        assertFalse(TableSuffixMatcher.matches("_bak", null));
    }

    @Test
    void treatsSuffixAsLiteralText() {
        assertFalse(TableSuffixMatcher.matches("%_bak", "db.orders_bak"), "通配符不做特殊解释");
        assertTrue(TableSuffixMatcher.matches("_2026", "tmp_2026"));
    }

    @Test
    void selectsMatchedTablesAndSkipsSystemTables() {
        List<String> tables = List.of("sys.catalog_bak", "kudu.tablets_bak", "db.b_bak", "db.A_bak", "other.x");

        List<String> selected = TableSuffixMatcher.select(tables, "_bak");

        assertEquals(List.of("db.A_bak", "db.b_bak"), selected);
        assertEquals(List.of("kudu.tablets_bak"), TableSuffixMatcher.systemMatched(tables, "s_bak"));
        assertEquals(List.of("sys.catalog_bak"), TableSuffixMatcher.systemMatched(tables, "catalog_bak"));
        assertEquals(0, TableSuffixMatcher.select(tables, "catalog_bak").size(), "系统表命中后缀也排除");
    }

    @Test
    void detectsOverlappingSuffixes() {
        assertTrue(TableSuffixMatcher.overlaps("_bak", "t1_bak"));
        assertTrue(TableSuffixMatcher.overlaps("t1_bak", "_bak"));
        assertTrue(TableSuffixMatcher.overlaps("_BAK", "t1_bak"), "忽略大小写");
        assertTrue(TableSuffixMatcher.overlaps("_bak", "bak"), "xx_bak 同时命中两者");
        assertFalse(TableSuffixMatcher.overlaps("_bak", "_tmp"));
        assertFalse(TableSuffixMatcher.overlaps("", "_bak"));
    }
}
