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
    void prefixDropConflictsWithRunningSuffixJob() {
        registry.create("DROP_SUFFIX", "c1", "_bak", JobRegistry.suffixKey("c1", "_bak"), "后缀 _bak");

        assertNotNull(DropConflicts.forPrefix(registry, "c1", "db.", List.of("db.t1_bak")));
        assertNull(DropConflicts.forPrefix(registry, "c1", "db.", List.of("db.t1_tmp")), "目标表不命中后缀则不冲突");
    }

    @Test
    void dbDropConflictsWithRunningPrefixJob() {
        registry.create("DROP_PREFIX", "c1", "db.", JobRegistry.prefixKey("c1", "db."), "前缀 db.");

        assertNotNull(DropConflicts.forDb(registry, "c1", "db", List.of("db.t1")));
        assertNull(DropConflicts.forDb(registry, "c1", "other", List.of("other.t1")));
    }

    @Test
    void dbDropConflictsWithRunningSuffixJob() {
        registry.create("DROP_SUFFIX", "c1", "_bak", JobRegistry.suffixKey("c1", "_bak"), "后缀 _bak");

        assertNotNull(DropConflicts.forDb(registry, "c1", "db", List.of("db.t1_bak")));
        assertNull(DropConflicts.forDb(registry, "c1", "db", List.of("db.t1_tmp")), "库内没有命中后缀的表则不冲突");
    }

    @Test
    void prefixDropsOnSamePrefixConflict() {
        registry.create("DROP_PREFIX", "c1", "db.tmp", JobRegistry.prefixKey("c1", "db.tmp"), "前缀 db.tmp");

        assertNotNull(DropConflicts.forPrefix(registry, "c1", "db.", List.of("db.tmp_a")));
    }

    @Test
    void suffixDropConflictsWithRunningTableJob() {
        registry.create("TRUNCATE", "c1", "db.t1_bak");

        String conflict = DropConflicts.forSuffix(registry, "c1", "_bak", List.of("db.t1_bak", "db.t2_bak"));

        assertNotNull(conflict);
        assertTrue(conflict.contains("db.t1_bak"));
        assertNull(DropConflicts.forSuffix(registry, "c1", "_tmp", List.of("db.t3_tmp")));
        assertNull(DropConflicts.forSuffix(registry, "c2", "_bak", List.of("db.t1_bak")), "平台之间互不影响");
    }

    @Test
    void suffixDropConflictsWithRunningDbJob() {
        registry.create("DROP_DB", "c1", "db", JobRegistry.dbKey("c1", "db"), "库 db");

        assertNotNull(DropConflicts.forSuffix(registry, "c1", "_bak", List.of("db.t1_bak")));
        assertNull(DropConflicts.forSuffix(registry, "c1", "_bak", List.of("other.t1_bak")), "目标表不在该库下则不冲突");
    }

    @Test
    void suffixDropConflictsWithRunningPrefixJob() {
        registry.create("DROP_PREFIX", "c1", "db.", JobRegistry.prefixKey("c1", "db."), "前缀 db.");

        assertNotNull(DropConflicts.forSuffix(registry, "c1", "_bak", List.of("db.t1_bak")));
        assertNull(DropConflicts.forSuffix(registry, "c1", "_bak", List.of("other.t1_bak")), "目标表不命中前缀则不冲突");
    }

    @Test
    void suffixDropsConflictWhenSuffixesOverlap() {
        registry.create("DROP_SUFFIX", "c1", "_bak", JobRegistry.suffixKey("c1", "_bak"), "后缀 _bak");

        assertNotNull(DropConflicts.forSuffix(registry, "c1", "t1_bak", List.of("db.t1_bak")));
        assertNull(DropConflicts.forSuffix(registry, "c1", "_tmp", List.of("db.t1_tmp")));
    }
}
