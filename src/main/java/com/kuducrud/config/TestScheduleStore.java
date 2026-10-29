package com.kuducrud.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kuducrud.model.TestSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 备份测试定时任务的持久化：config/test-schedules.json。
 * <p>文件损坏或不存在时返回空列表，不影响控制台启动。
 */
@Component
public class TestScheduleStore {

    private static final Logger log = LoggerFactory.getLogger(TestScheduleStore.class);

    private final ObjectMapper mapper;
    private final Path file;

    @Autowired
    public TestScheduleStore(ObjectMapper mapper) {
        this(mapper, Path.of("config", "test-schedules.json"));
    }

    TestScheduleStore(ObjectMapper mapper, Path file) {
        this.mapper = mapper;
        this.file = file;
    }

    public record State(List<TestSchedule> schedules) {
    }

    public synchronized List<TestSchedule> load() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            State state = mapper.readValue(file.toFile(), State.class);
            if (state == null || state.schedules() == null) {
                return List.of();
            }
            List<TestSchedule> valid = new ArrayList<>();
            for (TestSchedule schedule : state.schedules()) {
                if (schedule != null && schedule.id() != null && schedule.clusterId() != null
                        && schedule.tables() != null && !schedule.tables().isEmpty()) {
                    valid.add(schedule);
                }
            }
            return valid;
        } catch (Exception e) {
            log.warn("read {} failed: {}", file, e.getMessage());
            return List.of();
        }
    }

    public synchronized void save(List<TestSchedule> schedules) {
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), new State(schedules));
        } catch (Exception e) {
            log.warn("save {} failed: {}", file, e.getMessage());
        }
    }
}
