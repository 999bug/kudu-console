package com.kuducrud.service;

import com.kuducrud.model.TestActionSpec;
import com.kuducrud.web.ApiException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestActionRulesTest {

    @Test
    void fillsDefaultsForDisabledActions() {
        TestActionSpec spec = TestActionRules.normalize(
                new TestActionSpec(null, null, null, null, null, null, null));

        assertEquals(TestActionSpec.DEFAULT_COUNT, spec.deleteCount());
        assertEquals(TestActionSpec.DEFAULT_COUNT, spec.addCount());
        assertEquals("STRING", spec.columnType());
        assertEquals(TestActionSpec.MODE_REPLACE, spec.columnMode());
        assertEquals(0, spec.enabledActions());
    }

    @Test
    void rejectsEmptyActionSelection() {
        ApiException error = assertThrows(ApiException.class,
                () -> TestActionRules.validate(new TestActionSpec(false, 5, false, 5, false, "STRING", "REPLACE")));
        assertTrue(error.getMessage().contains("至少选择一个"));
    }

    @Test
    void rejectsOutOfRangeCounts() {
        assertThrows(ApiException.class,
                () -> TestActionRules.validate(new TestActionSpec(true, 0, false, 5, false, null, null)));
        assertThrows(ApiException.class,
                () -> TestActionRules.validate(new TestActionSpec(true, 101, false, 5, false, null, null)));
    }

    @Test
    void rejectsUnsupportedColumnType() {
        assertThrows(ApiException.class,
                () -> TestActionRules.validate(new TestActionSpec(false, 5, false, 5, true, "BINARY", "REPLACE")));
        assertThrows(ApiException.class,
                () -> TestActionRules.validate(new TestActionSpec(false, 5, false, 5, true, "STRING", "ALWAYS")));
    }

    @Test
    void normalizesTablesAndRejectsSystemTables() {
        List<String> duplicates = new ArrayList<>(List.of("db.t1", " db.t1 ", "db.t2"));
        assertEquals(List.of("db.t1", "db.t2"), TestActionRules.normalizeTables(duplicates));

        assertThrows(ApiException.class, () -> TestActionRules.normalizeTables(List.of("sys.catalog")));
        assertThrows(ApiException.class, () -> TestActionRules.normalizeTables(List.of()));
    }

    @Test
    void capsTableCount() {
        List<String> tables = new ArrayList<>();
        for (int i = 0; i <= TestActionRules.MAX_TABLES; i++) {
            tables.add("db.t" + i);
        }
        ApiException error = assertThrows(ApiException.class, () -> TestActionRules.normalizeTables(tables));
        assertTrue(error.getMessage().contains(String.valueOf(TestActionRules.MAX_TABLES)));
    }
}
