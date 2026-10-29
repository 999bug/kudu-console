package com.kuducrud.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 备份测试调度引擎：每 5 秒扫描一次到期任务。
 * <p>不做 cron 解析，固定间隔足够覆盖「全量备份完成后、增量备份前」这类测试窗口。
 */
@Component
public class TestScheduleEngine {

    private static final Logger log = LoggerFactory.getLogger(TestScheduleEngine.class);

    private final TestScheduleService schedules;

    public TestScheduleEngine(TestScheduleService schedules) {
        this.schedules = schedules;
    }

    @Scheduled(fixedDelay = 5_000)
    public void tick() {
        try {
            schedules.runDue(System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("test schedule tick failed: {}", e.getMessage());
        }
    }
}
