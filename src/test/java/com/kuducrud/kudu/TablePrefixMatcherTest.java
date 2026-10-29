package com.kuducrud.kudu;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TablePrefixMatcherTest {

    @Test
    void matchesFullNameAndImpalaStrippedName() {
        assertTrue(TablePrefixMatcher.matches("db.", "db.orders"));
        assertTrue(TablePrefixMatcher.matches("db.", "impala::db.orders"));
        assertTrue(TablePrefixMatcher.matches("impala::db.", "impala::db.orders"));
        assertTrue(TablePrefixMatcher.matches("DB.", "db.orders"), "前缀匹配忽略大小写");
        assertFalse(TablePrefixMatcher.matches("db.", "other.orders"));
        assertFalse(TablePrefixMatcher.matches("  ", "db.orders"));
    }

    @Test
    void treatsPrefixAsLiteralText() {
        assertFalse(TablePrefixMatcher.matches("db_%", "db.orders"), "通配符不做特殊解释");
        assertTrue(TablePrefixMatcher.matches("tmp_", "tmp_2026"));
    }

    @Test
    void selectsMatchedTablesAndSkipsSystemTables() {
        List<String> tables = List.of("sys.catalog", "kudu.tablets", "db.b", "db.A", "other.x");

        List<String> selected = TablePrefixMatcher.select(tables, "db.");

        assertEquals(List.of("db.A", "db.b"), selected);
        assertEquals(List.of("kudu.tablets"), TablePrefixMatcher.systemMatched(tables, "k"));
        assertEquals(List.of("sys.catalog"), TablePrefixMatcher.systemMatched(tables, "sys."));
        assertEquals(0, TablePrefixMatcher.select(tables, "sys.").size());
    }

    @Test
    void detectsOverlappingPrefixes() {
        assertTrue(TablePrefixMatcher.overlaps("db.", "db.tmp"));
        assertTrue(TablePrefixMatcher.overlaps("db.tmp", "db."));
        assertTrue(TablePrefixMatcher.overlaps("impala::db.", "db."));
        assertTrue(TablePrefixMatcher.overlaps("DB.", "db.tmp"));
        assertFalse(TablePrefixMatcher.overlaps("db.", "other."));
        assertFalse(TablePrefixMatcher.overlaps("", "db."));
    }
}
