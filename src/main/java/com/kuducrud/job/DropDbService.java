package com.kuducrud.job;

import com.kuducrud.kudu.KuduConnectionManager;
import com.kuducrud.kudu.TableNameUtil;
import com.kuducrud.model.JobView;
import com.kuducrud.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 删除「库」（Kudu 没有真正的 catalog，库只是表名前缀）：把该前缀下的所有表逐个删除。
 * <p>表多时耗时长，所以走异步任务，可看进度、可取消；单个表删除走同步接口即可。
 */
public class DropDbService {

    private static final Logger log = LoggerFactory.getLogger(DropDbService.class);

    private final KuduConnectionManager connection;
    private final JobRegistry registry;
    private final String clusterId;

    public DropDbService(KuduConnectionManager connection, JobRegistry registry, String clusterId) {
        this.connection = connection;
        this.registry = registry;
        this.clusterId = clusterId;
    }

    public JobView submit(String db, String confirm, Boolean acknowledge) {
        String name = db == null ? "" : db.trim();
        if (name.isEmpty()) {
            throw ApiException.badRequest("库名不能为空");
        }
        if (!name.equals(confirm)) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "CONFIRM_REQUIRED",
                    "危险操作需要确认：请在 confirm 字段原样填入库名 " + name);
        }
        if (!Boolean.TRUE.equals(acknowledge)) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "CONFIRM_REQUIRED",
                    "请确认已知晓该库下所有表都会被删除（acknowledge=true）");
        }
        if ("sys".equalsIgnoreCase(name) || "kudu".equalsIgnoreCase(name)) {
            throw ApiException.badRequest("不允许删除系统库：" + name);
        }

        List<String> tables = tablesOf(name);
        if (tables.isEmpty()) {
            throw ApiException.notFound("库 " + name + " 下没有表");
        }
        if (registry.anyRunningInDb(clusterId, name)) {
            throw ApiException.conflict("JOB_CONFLICT",
                    "库 " + name + " 下有表正在执行破坏性任务，请等待其结束");
        }

        JobHandle handle = registry.create("DROP_DB", clusterId, name,
                JobRegistry.dbKey(clusterId, name), "库 " + name);
        handle.totalEstimate().set(tables.size());
        registry.submit(handle, () -> run(handle, tables));
        log.info("drop-db job submitted: db={}, tables={}", name, tables.size());
        return handle.toView();
    }

    /** 该前缀下的所有表；Impala 托管的 impala::db.table 也归入同一库。 */
    public List<String> tablesOf(String db) {
        List<String> result = new ArrayList<>();
        for (String table : connection.listTables()) {
            if (db.equalsIgnoreCase(TableNameUtil.dbOf(table))) {
                result.add(table);
            }
        }
        return result;
    }

    private void run(JobHandle handle, List<String> tables) {
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
            log.info("drop-db done: db={}, dropped={}, failed={}", handle.table(), dropped, failed);
        } catch (Throwable e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            handle.markFinished("FAILED", "删库中断：" + message);
            log.warn("drop-db failed: db={}, dropped={}, reason={}", handle.table(), dropped, message);
        } finally {
            handle.processed().set(Math.min(tables.size(), handle.processed().get()));
        }
    }
}
