package com.kuducrud.service;

import com.kuducrud.config.ClusterRegistry;
import com.kuducrud.config.ClusterRuntime;
import com.kuducrud.config.TestScheduleStore;
import com.kuducrud.job.JobHandle;
import com.kuducrud.job.JobRegistry;
import com.kuducrud.model.JobView;
import com.kuducrud.model.TestActionSpec;
import com.kuducrud.model.TestRunRecord;
import com.kuducrud.model.TestSchedule;
import com.kuducrud.model.TestScheduleRequest;
import com.kuducrud.web.ApiException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 备份测试定时任务：保存配置、按固定间隔触发、记录最近执行结果。
 * <p>调度状态存在 config/test-schedules.json，重启后继续；同一任务上一轮未结束时跳过本轮。
 */
@Service
public class TestScheduleService {

    private static final Logger log = LoggerFactory.getLogger(TestScheduleService.class);
    private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final int DEFAULT_INTERVAL_SECONDS = 300;
    private static final int MIN_INTERVAL_SECONDS = 60;
    private static final int MAX_INTERVAL_SECONDS = 86_400;
    private static final int MAX_RUNS_LIMIT = 10_000;
    private static final int HISTORY_LIMIT = 20;

    private final ClusterRegistry registry;
    private final TestScheduleStore store;
    private final JobRegistry jobs;

    private final Map<String, TestSchedule> schedules = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();
    private final Map<String, Deque<TestRunRecord>> history = new ConcurrentHashMap<>();

    public TestScheduleService(ClusterRegistry registry, TestScheduleStore store, JobRegistry jobs) {
        this.registry = registry;
        this.store = store;
        this.jobs = jobs;
    }

    @PostConstruct
    public void init() {
        for (TestSchedule schedule : store.load()) {
            schedules.put(schedule.id(), schedule);
        }
        log.info("test schedules loaded: {}", schedules.size());
    }

    public List<TestSchedule> list() {
        return schedules.values().stream()
                .sorted(Comparator.comparingLong(TestSchedule::createdAtMs).reversed())
                .toList();
    }

    public TestSchedule get(String id) {
        TestSchedule schedule = schedules.get(id);
        if (schedule == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND", "定时任务不存在：" + id);
        }
        return schedule;
    }

    public synchronized TestSchedule create(String clusterId, TestScheduleRequest request) {
        Validated valid = validate(request, null);
        long now = System.currentTimeMillis();
        boolean enabled = request.enabled() == null || request.enabled();
        TestSchedule schedule = new TestSchedule(
                newId(), valid.name(), clusterId, enabled, valid.intervalSeconds(), valid.maxRuns(), valid.endAtMs(),
                valid.tables(), valid.actions(), 0,
                enabled ? now + valid.intervalSeconds() * 1000L : null,
                null, null, null, Map.of(), now, now);
        schedules.put(schedule.id(), schedule);
        save();
        log.info("test schedule created: {} ({} tables, every {}s)",
                schedule.name(), schedule.tables().size(), schedule.intervalSeconds());
        return schedule;
    }

    public synchronized TestSchedule update(String id, TestScheduleRequest request) {
        TestSchedule existing = get(id);
        if (isRunning(id)) {
            throw ApiException.conflict("JOB_CONFLICT", "定时任务正在执行，暂时不能修改");
        }
        Validated valid = validate(request, existing);
        long now = System.currentTimeMillis();
        boolean enabled = request.enabled() == null ? existing.enabled() : request.enabled();
        if (enabled && reachedLimit(existing, now, 0)) {
            throw ApiException.badRequest("已达到最大执行次数或结束时间，请调整后再启用");
        }
        TestSchedule updated = new TestSchedule(
                existing.id(), valid.name(), existing.clusterId(), enabled,
                valid.intervalSeconds(), valid.maxRuns(), valid.endAtMs(), valid.tables(), valid.actions(),
                existing.runCount(),
                enabled ? now + valid.intervalSeconds() * 1000L : null,
                existing.lastRunAtMs(), existing.lastState(), existing.lastMessage(),
                existing.lastColumns() == null ? Map.of() : existing.lastColumns(),
                existing.createdAtMs(), now);
        schedules.put(id, updated);
        save();
        log.info("test schedule updated: {}", updated.name());
        return updated;
    }

    public synchronized void delete(String id) {
        get(id);
        if (isRunning(id)) {
            throw ApiException.conflict("JOB_CONFLICT", "定时任务正在执行，暂时不能删除");
        }
        schedules.remove(id);
        history.remove(id);
        running.remove(id);
        save();
        log.info("test schedule deleted: {}", id);
    }

    public synchronized TestSchedule toggle(String id, boolean enabled) {
        TestSchedule existing = get(id);
        if (enabled && reachedLimit(existing, System.currentTimeMillis(), 0)) {
            throw ApiException.badRequest("已达到最大执行次数或结束时间，请调整后再启用");
        }
        long now = System.currentTimeMillis();
        TestSchedule updated = new TestSchedule(
                existing.id(), existing.name(), existing.clusterId(), enabled,
                existing.intervalSeconds(), existing.maxRuns(), existing.endAtMs(),
                existing.tables(), existing.actions(), existing.runCount(),
                enabled ? now + existing.intervalSeconds() * 1000L : null,
                existing.lastRunAtMs(), existing.lastState(), existing.lastMessage(),
                existing.lastColumns() == null ? Map.of() : existing.lastColumns(),
                existing.createdAtMs(), now);
        schedules.put(id, updated);
        save();
        return updated;
    }

    public JobView runNow(String id) {
        return submitRun(get(id), "MANUAL");
    }

    public synchronized List<TestRunRecord> runs(String id) {
        get(id);
        Deque<TestRunRecord> deque = history.get(id);
        return deque == null ? List.of() : new ArrayList<>(deque);
    }

    /** 调度引擎每 5 秒调用一次：找出到期任务并提交，错过的时间点只补跑一次。 */
    public void runDue(long now) {
        for (TestSchedule schedule : list()) {
            if (schedule.endAtMs() != null && now >= schedule.endAtMs()) {
                disable(schedule, "已到结束时间，自动暂停");
                continue;
            }
            if (schedule.maxRuns() != null && schedule.runCount() >= schedule.maxRuns()) {
                disable(schedule, "已达到最大执行次数，自动暂停");
                continue;
            }
            if (!schedule.enabled() || schedule.nextRunAtMs() == null || schedule.nextRunAtMs() > now) {
                continue;
            }
            try {
                submitRun(schedule, "SCHEDULE");
            } catch (Exception e) {
                if (e instanceof ApiException api && "CLUSTER_NOT_FOUND".equals(api.getCode())) {
                    disable(schedule, "平台已删除，自动暂停");
                    continue;
                }
                String message = messageOf(e);
                recordSkip(schedule.id(), message, now);
                log.warn("test schedule skipped: {} ({})", schedule.name(), message);
            }
        }
    }

    private synchronized void disable(TestSchedule schedule, String reason) {
        TestSchedule current = schedules.get(schedule.id());
        if (current == null || !current.enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        TestSchedule updated = new TestSchedule(
                current.id(), current.name(), current.clusterId(), false,
                current.intervalSeconds(), current.maxRuns(), current.endAtMs(),
                current.tables(), current.actions(), current.runCount(), null,
                current.lastRunAtMs(), "PAUSED", reason,
                current.lastColumns() == null ? Map.of() : current.lastColumns(),
                current.createdAtMs(), now);
        schedules.put(current.id(), updated);
        save();
    }

    private JobView submitRun(TestSchedule schedule, String trigger) {
        AtomicBoolean flag = running.computeIfAbsent(schedule.id(), key -> new AtomicBoolean());
        if (!flag.compareAndSet(false, true)) {
            throw ApiException.conflict("JOB_CONFLICT", "上一次执行还没有结束");
        }
        long startedAt = System.currentTimeMillis();
        try {
            ClusterRuntime runtime = registry.runtime(schedule.clusterId());
            runtime.testActions().checkConflicts(schedule.tables());

            String target = schedule.tables().size() == 1
                    ? schedule.tables().get(0) : schedule.tables().size() + " 张表";
            JobHandle handle = jobs.createBatch("TEST_SCHEDULE", schedule.clusterId(), target,
                    schedule.tables(), "定时测试 " + schedule.name());
            handle.totalEstimate().set((long) schedule.tables().size() * schedule.actions().enabledActions());
            jobs.submit(handle, () -> {
                TestActionService.RunResult result = null;
                try {
                    result = runtime.testActions().execute(handle, schedule.tables(),
                            schedule.actions(), schedule.lastColumns());
                } catch (Throwable e) {
                    if (!handle.isDone()) {
                        handle.markFinished("FAILED", "测试调度执行失败：" + messageOf(e));
                    }
                } finally {
                    finishRun(schedule.id(), trigger, startedAt, handle, result);
                    flag.set(false);
                }
            });
            return handle.toView();
        } catch (RuntimeException e) {
            flag.set(false);
            throw e;
        }
    }

    private synchronized void finishRun(String scheduleId, String trigger, long startedAt,
                                        JobHandle handle, TestActionService.RunResult result) {
        TestSchedule schedule = schedules.get(scheduleId);
        if (schedule == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, String> columns = new HashMap<>(
                schedule.lastColumns() == null ? Map.of() : schedule.lastColumns());
        if (result != null) {
            columns.putAll(result.lastColumns());
        }
        columns.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null);
        int runCount = schedule.runCount() + 1;
        boolean maxReached = schedule.maxRuns() != null && runCount >= schedule.maxRuns();
        boolean expired = schedule.endAtMs() != null && now >= schedule.endAtMs();
        boolean enabled = schedule.enabled() && !maxReached && !expired;
        Long next = enabled ? now + schedule.intervalSeconds() * 1000L : null;

        TestSchedule updated = new TestSchedule(
                schedule.id(), schedule.name(), schedule.clusterId(), enabled,
                schedule.intervalSeconds(), schedule.maxRuns(), schedule.endAtMs(),
                schedule.tables(), schedule.actions(), runCount, next,
                now, handle.state(), handle.message(), Map.copyOf(columns),
                schedule.createdAtMs(), now);
        schedules.put(scheduleId, updated);
        addHistory(new TestRunRecord(newId(), scheduleId, trigger, startedAt, now,
                handle.state(), handle.message(), result == null ? List.of() : result.tables()));
        save();
    }

    private synchronized void recordSkip(String scheduleId, String message, long now) {
        TestSchedule schedule = schedules.get(scheduleId);
        if (schedule == null) {
            return;
        }
        Long next = schedule.enabled() ? now + schedule.intervalSeconds() * 1000L : null;
        TestSchedule updated = new TestSchedule(
                schedule.id(), schedule.name(), schedule.clusterId(), schedule.enabled(),
                schedule.intervalSeconds(), schedule.maxRuns(), schedule.endAtMs(),
                schedule.tables(), schedule.actions(), schedule.runCount(), next,
                now, "SKIPPED", message,
                schedule.lastColumns() == null ? Map.of() : schedule.lastColumns(),
                schedule.createdAtMs(), now);
        schedules.put(scheduleId, updated);
        addHistory(new TestRunRecord(newId(), scheduleId, "SCHEDULE", now, now,
                "SKIPPED", message, List.of()));
        save();
    }

    private void addHistory(TestRunRecord record) {
        Deque<TestRunRecord> deque = history.computeIfAbsent(record.scheduleId(), key -> new ArrayDeque<>());
        deque.addFirst(record);
        while (deque.size() > HISTORY_LIMIT) {
            deque.removeLast();
        }
    }

    private boolean isRunning(String id) {
        AtomicBoolean flag = running.get(id);
        return flag != null && flag.get();
    }

    private static boolean reachedLimit(TestSchedule schedule, long now, int extraRuns) {
        if (schedule.maxRuns() != null && schedule.runCount() + extraRuns >= schedule.maxRuns()) {
            return true;
        }
        return schedule.endAtMs() != null && now >= schedule.endAtMs();
    }

    private void save() {
        store.save(list());
    }

    private record Validated(String name, int intervalSeconds, Integer maxRuns, Long endAtMs,
                             List<String> tables, TestActionSpec actions) {
    }

    private static Validated validate(TestScheduleRequest request, TestSchedule existing) {
        if (request == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            name = existing != null ? existing.name() : "备份测试 " + NAME_TIME.format(LocalDateTime.now());
        }
        if (name.length() > 64) {
            throw ApiException.badRequest("任务名称不能超过 64 个字符");
        }

        int interval = request.intervalSeconds() == null
                ? (existing == null ? DEFAULT_INTERVAL_SECONDS : existing.intervalSeconds())
                : request.intervalSeconds();
        if (interval < MIN_INTERVAL_SECONDS || interval > MAX_INTERVAL_SECONDS) {
            throw ApiException.badRequest("执行间隔需在 " + MIN_INTERVAL_SECONDS + ".."
                    + MAX_INTERVAL_SECONDS + " 秒");
        }

        Integer maxRuns = request.maxRuns() == null
                ? (existing == null ? null : existing.maxRuns()) : request.maxRuns();
        if (maxRuns != null && (maxRuns < 1 || maxRuns > MAX_RUNS_LIMIT)) {
            throw ApiException.badRequest("最多执行次数需在 1.." + MAX_RUNS_LIMIT);
        }

        Long endAt = request.endAtMs() == null
                ? (existing == null ? null : existing.endAtMs()) : request.endAtMs();
        if (request.endAtMs() != null && endAt <= System.currentTimeMillis()) {
            throw ApiException.badRequest("结束时间必须晚于当前时间");
        }

        List<String> tables = TestActionRules.normalizeTables(request.tables());
        TestActionSpec actions = TestActionRules.normalize(request.actions());
        TestActionRules.validate(actions);
        return new Validated(name, interval, maxRuns, endAt, tables, actions);
    }

    private static String newId() {
        return "ts" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
