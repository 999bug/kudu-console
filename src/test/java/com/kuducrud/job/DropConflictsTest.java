package com.kuducrud.job;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropConflictsTest {

    private final JobRegistry registry = new JobRegistry();

    @AfterEach
    void shutdown() {
        registry.shutdown();
    }

    @Test
    void prefixDropConflictsWithRunningTableJob() {
        registry.create("TRUNCATE", "c1", "db.t1");

        String conflict = DropConflicts.forPrefix(registry, "c1", "db.", List.of("db.t1", "db.t2"));

        assertNotNull(conflict);
        assertTrue(conflict.contains("db.t1"));
        assertNull(DropConflicts.forPrefix(registry, "c1", "other.", List.of("other.x")));
        assertNull(DropConflicts.forPrefix(registry, "c2", "db.", List.of("db.t1")), "平台之间互不影响");
    }

    @Test
    void prefixDropConflictsWithRunningDbJob() {
        registry.create("DROP_DB", "c1", "db", JobRegistry.dbKey("c1", "db"), "库 db");

        assertNotNull(DropConflicts.forPrefix(registry, "c1", "db.tmp", List.of("db.tmp")));
        assertNull(DropConflicts.forPrefix(registry, "c1", "other.", List.of("other.x")));
    }

    @Test
    void dbDropConflictsWithRunningPrefixJob() {
        registry.create("DROP_PREFIX", "c1", "db.", JobRegistry.prefixKey("c1", "db."), "前缀 db.");

        assertNotNull(DropConflicts.forDb(registry, "c1", "db"));
        assertNull(DropConflicts.forDb(registry, "c1", "other"));
    }

    @Test
    void prefixDropsOnSamePrefixConflict() {
        registry.create("DROP_PREFIX", "c1", "db.tmp", JobRegistry.prefixKey("c1", "db.tmp"), "前缀 db.tmp");

        assertNotNull(DropConflicts.forPrefix(registry, "c1", "db.", List.of("db.tmp_a")));
    }
}
