package com.kuducrud.job;

import com.kuducrud.model.JobView;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异步任务句柄。进度字段用原子量，读侧无需加锁。
 */
public class JobHandle {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final String id;
    private final String type;
    private final String clusterId;
    private final String table;
    private final long createdAt = System.currentTimeMillis();

    private volatile String state = "PENDING";
    private volatile String message;
    private volatile long startedAt;
    private volatile long finishedAt;

    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong deleted = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong totalEstimate = new AtomicLong(-1);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    public JobHandle(String id, String type, String clusterId, String table) {
        this.id = id;
        this.type = type;
        this.clusterId = clusterId;
        this.table = table;
    }

    public String id() {
        return id;
    }

    public String type() {
        return type;
    }

    public String clusterId() {
        return clusterId;
    }

    public String table() {
        return table;
    }

    public long createdAt() {
        return createdAt;
    }

    public String state() {
        return state;
    }

    public void state(String value) {
        this.state = value;
    }

    public String message() {
        return message;
    }

    public void message(String value) {
        this.message = value;
    }

    public void markStarted() {
        this.startedAt = System.currentTimeMillis();
        this.state = "RUNNING";
    }

    public void markFinished(String finalState, String finalMessage) {
        this.finishedAt = System.currentTimeMillis();
        this.message = finalMessage;
        this.state = finalState;
    }

    public boolean isDone() {
        return finishedAt > 0;
    }

    public boolean isCancelable() {
        return !isDone() && !cancelRequested.get();
    }

    public void requestCancel() {
        cancelRequested.set(true);
    }

    public boolean cancelRequested() {
        return cancelRequested.get();
    }

    public AtomicLong processed() {
        return processed;
    }

    public AtomicLong deleted() {
        return deleted;
    }

    public AtomicLong failed() {
        return failed;
    }

    public AtomicLong totalEstimate() {
        return totalEstimate;
    }

    public JobView toView() {
        long elapsed = (startedAt == 0 ? 0 : (finishedAt == 0 ? System.currentTimeMillis() : finishedAt) - startedAt);
        double rate = elapsed > 0 ? processed.get() * 1000.0 / elapsed : 0.0;
        return new JobView(
                id,
                type,
                clusterId,
                table,
                state,
                processed.get(),
                deleted.get(),
                failed.get(),
                totalEstimate.get() < 0 ? null : totalEstimate.get(),
                elapsed,
                Math.round(rate * 10) / 10.0,
                message,
                isCancelable(),
                startedAt == 0 ? null : TIME.format(Instant.ofEpochMilli(startedAt)),
                finishedAt == 0 ? null : TIME.format(Instant.ofEpochMilli(finishedAt))
        );
    }
}
