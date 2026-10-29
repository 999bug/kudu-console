package com.kuducrud.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchComparePlannerTest {

    @Test
    void tablesOfDbFiltersByDbAndSkipsSystemTables() {
        List<String> tables = List.of(
                "t_1028_.t1", "t_1028_.t2", "impala::t_1028_.t3",
                "other.t9", "sys.tables", "kudu.local_table", "nodefault");

        List<String> result = BatchComparePlanner.tablesOfDb(tables, "t_1028_");

        assertEquals(List.of("impala::t_1028_.t3", "t_1028_.t1", "t_1028_.t2"), result);
    }

    @Test
    void tablesOfDbIgnoresCase() {
        List<String> result = BatchComparePlanner.tablesOfDb(List.of("T_1028_.T1"), "t_1028_");
        assertEquals(List.of("T_1028_.T1"), result);
    }

    @Test
    void planPairsUnionOfShortNames() {
        List<BatchComparePlanner.Pair> pairs = BatchComparePlanner.plan(
                List.of("t_1028_.t1", "t_1028_.t2", "t_1028_.t3"),
                List.of("rec_t_1028_.t1", "rec_t_1028_.t2", "rec_t_1028_.t4"));

        assertEquals(4, pairs.size());
        assertEquals(new BatchComparePlanner.Pair("t1", "t_1028_.t1", "rec_t_1028_.t1"), pairs.get(0));
        assertEquals(new BatchComparePlanner.Pair("t2", "t_1028_.t2", "rec_t_1028_.t2"), pairs.get(1));
        assertEquals(new BatchComparePlanner.Pair("t3", "t_1028_.t3", null), pairs.get(2));
        assertEquals(new BatchComparePlanner.Pair("t4", null, "rec_t_1028_.t4"), pairs.get(3));
    }

    @Test
    void planKeepsActualTableNamesWithImpalaPrefix() {
        List<BatchComparePlanner.Pair> pairs = BatchComparePlanner.plan(
                List.of("impala::t_1028_.t1"),
                List.of("rec_t_1028_.t1"));

        assertEquals(1, pairs.size());
        assertEquals("impala::t_1028_.t1", pairs.get(0).leftTable());
        assertEquals("rec_t_1028_.t1", pairs.get(0).rightTable());
    }

    @Test
    void planHandlesEmptySides() {
        assertTrue(BatchComparePlanner.plan(List.of(), List.of()).isEmpty());

        List<BatchComparePlanner.Pair> onlyRight = BatchComparePlanner.plan(List.of(), List.of("rec_t_1028_.t1"));
        assertEquals(1, onlyRight.size());
        assertNull(onlyRight.get(0).leftTable());
    }
}
