package com.kuducrud.job;

import com.kuducrud.kudu.KuduConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 批量删表的公共执行体：逐张删除，单张失败不影响其余表，可中途取消，结果写进任务句柄。
 * <p>删库与按前缀删表共用，区别只在于「要删哪些表」由调用方算好。
 */
final class TableBatchDropper {

    private static final Logger log = LoggerFactory.getLogger(TableBatchDropper.class);

    private TableBatchDropper() {
    }

    static void run(KuduConnectionManager connection, JobHandle handle, List<String> tables, String action) {
        handle.markStarted();
        int dropped = 0;
        int failed = 0;
        StringBuilder failureDetail = new StringBuilder();
        try {
            for (String table : tables) {
                if (handle.cancelRequested()) {
                    break;
                }
                try {
                    connection.client().deleteTable(table);
                    connection.invalidate(table);
                    dropped++;
                    handle.deleted().incrementAndGet();
                } catch (Exception e) {
                    failed++;
                    handle.failed().incrementAndGet();
                    if (failureDetail.length() < 400) {
                        failureDetail.append(table).append(": ")
                                .append(e.getMessage() == null ? e.toString() : e.getMessage()).append("; ");
                    }
                }
                handle.processed().incrementAndGet();
            }

            String summary = "已删除 " + dropped + "/" + tables.size() + " 张表";
            if (failed > 0) {
                summary += "，失败 " + failed + " 张：" + failureDetail;
            }
            if (handle.cancelRequested()) {
                handle.markFinished("CANCELED", "已取消，" + summary);
            } else if (failed > 0) {
                handle.markFinished("FAILED", summary);
            } else {
                handle.markFinished("SUCCEEDED", summary);
            }
            log.info("{} done: target={}, dropped={}, failed={}", handle.type(), handle.table(), dropped, failed);
        } catch (Throwable e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            handle.markFinished("FAILED", action + "中断：" + message);
            log.warn("{} failed: target={}, dropped={}, reason={}",
                    handle.type(), handle.table(), dropped, message);
        } finally {
            handle.processed().set(Math.min(tables.size(), handle.processed().get()));
        }
    }
}
