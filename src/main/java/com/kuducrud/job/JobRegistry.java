package com.kuducrud.job;

import com.kuducrud.model.JobView;
import com.kuducrud.web.ApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务注册表：同一张表同时只允许一个破坏性任务，最多保留 50 条历史。
 */
@Component
public class JobRegistry {

    private static final Logger log = LoggerFactory.getLogger(JobRegistry.class);
    private static final int MAX_JOBS = 50;

    private final Map<String, JobHandle> jobs = new ConcurrentHashMap<>();
    private final Set<String> runningTables = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor;

    public JobRegistry() {
        AtomicInteger seq = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "kudu-job-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public JobHandle create(String type, String clusterId, String table) {
        if (!runningTables.add(clusterId + "|" + table)) {
            throw ApiException.conflict("JOB_CONFLICT", "表 " + table + " 已有正在运行的破坏性任务");
        }
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        JobHandle handle = new JobHandle(id, type, clusterId, table);
        jobs.put(id, handle);
        trim();
        return handle;
    }

    public void submit(JobHandle handle, Runnable task) {
        executor.submit(() -> {
            try {
                task.run();
            } catch (Throwable e) {
                log.error("job {} failed", handle.id(), e);
                if (!handle.isDone()) {
                    handle.markFinished("FAILED", e.getMessage() == null ? e.toString() : e.getMessage());
                }
            } finally {
                runningTables.remove(handle.clusterId() + "|" + handle.table());
            }
        });
    }

    public void release(JobHandle handle) {
        runningTables.remove(handle.clusterId() + "|" + handle.table());
    }

    public JobHandle get(String id) {
        JobHandle handle = jobs.get(id);
        if (handle == null) {
            throw ApiException.notFound("任务不存在：" + id);
        }
        return handle;
    }

    public List<JobView> list() {
        List<JobHandle> handles = new ArrayList<>(jobs.values());
        handles.sort(Comparator.comparingLong(JobHandle::createdAt).reversed());
        return handles.stream().map(JobHandle::toView).toList();
    }

    /** 清理 30 分钟前结束的任务。 */
    @Scheduled(fixedDelay = 60_000)
    public void cleanup() {
        long cutoff = System.currentTimeMillis() - 30 * 60_000L;
        jobs.entrySet().removeIf(entry -> {
            JobHandle handle = entry.getValue();
            return handle.isDone() && handle.createdAt() < cutoff;
        });
    }

    private void trim() {
        if (jobs.size() <= MAX_JOBS) {
            return;
        }
        List<JobHandle> finished = jobs.values().stream()
                .filter(JobHandle::isDone)
                .sorted(Comparator.comparingLong(JobHandle::createdAt))
                .toList();
        int toRemove = jobs.size() - MAX_JOBS;
        for (int i = 0; i < Math.min(toRemove, finished.size()); i++) {
            jobs.remove(finished.get(i).id());
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
