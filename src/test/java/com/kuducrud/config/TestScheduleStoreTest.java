package com.kuducrud.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kuducrud.model.TestActionSpec;
import com.kuducrud.model.TestSchedule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestScheduleStoreTest {

    @TempDir
    Path dir;

    @Test
    void roundTripsSchedules() {
        ObjectMapper mapper = new ObjectMapper();
        TestScheduleStore store = new TestScheduleStore(mapper, dir.resolve("schedules.json"));
        TestSchedule schedule = new TestSchedule(
                "ts1", "备份测试", "c1", true, 300, 10, null,
                List.of("db.t1", "db.t2"),
                new TestActionSpec(true, 5, true, 3, true, "STRING", "REPLACE"),
                2, 1_700_000_300_000L, 1_700_000_000_000L, "SUCCEEDED", "完成 2/2 张表",
                Map.of("db.t1", "bk_test_1"), 1_700_000_000_000L, 1_700_000_300_000L);

        store.save(List.of(schedule));
        List<TestSchedule> loaded = store.load();

        assertEquals(1, loaded.size());
        TestSchedule back = loaded.get(0);
        assertEquals("ts1", back.id());
        assertEquals(List.of("db.t1", "db.t2"), back.tables());
        assertEquals(3, back.actions().addCount());
        assertEquals("bk_test_1", back.lastColumns().get("db.t1"));
        assertEquals(2, back.runCount());
    }

    @Test
    void returnsEmptyWhenFileIsMissingOrBroken() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TestScheduleStore missing = new TestScheduleStore(mapper, dir.resolve("missing.json"));
        assertTrue(missing.load().isEmpty());

        Path brokenFile = dir.resolve("broken.json");
        Files.writeString(brokenFile, "{not json");
        TestScheduleStore broken = new TestScheduleStore(mapper, brokenFile);
        assertTrue(broken.load().isEmpty());
    }
}
