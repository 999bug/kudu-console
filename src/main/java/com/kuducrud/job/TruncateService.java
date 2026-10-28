package com.kuducrud.job;

import com.kuducrud.config.KuduProperties;
import com.kuducrud.kudu.KuduConnectionManager;
import com.kuducrud.kudu.KuduValueConverter;
import com.kuducrud.kudu.TableNameUtil;
import com.kuducrud.model.JobView;
import com.kuducrud.web.ApiException;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Schema;
import org.apache.kudu.client.AsyncKuduScanner;
import org.apache.kudu.client.KuduScanner;
import org.apache.kudu.client.KuduSession;
import org.apache.kudu.client.KuduTable;
import org.apache.kudu.client.Operation;
import org.apache.kudu.client.OperationResponse;
import org.apache.kudu.client.RowResult;
import org.apache.kudu.client.SessionConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 清空表数据（Kudu 没有 truncate）：只扫描主键，分批 Delete。
 * <p>默认异步执行，可查看进度与取消；行数超过上限时直接失败并建议改用 DROP。
 */
public class TruncateService {

    private static final Logger log = LoggerFactory.getLogger(TruncateService.class);

    private final KuduConnectionManager connection;
    private final JobRegistry registry;
    private final KuduProperties props;
    private final String clusterId;

    public TruncateService(KuduConnectionManager connection, JobRegistry registry,
                           KuduProperties props, String clusterId) {
        this.connection = connection;
        this.registry = registry;
        this.props = props;
        this.clusterId = clusterId;
    }

    public JobView submit(String table, String confirm, Boolean acknowledge) {
        if (table == null || table.isBlank()) {
            throw ApiException.badRequest("表名不能为空");
        }
        if (!table.equals(confirm)) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "CONFIRM_REQUIRED",
                    "清空操作需要确认：请在 confirm 字段原样填入表名 " + table);
        }
        if (!Boolean.TRUE.equals(acknowledge)) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "CONFIRM_REQUIRED",
                    "请确认已知晓数据不可恢复（acknowledge=true）");
        }
        if (TableNameUtil.isSystemTable(table)) {
            throw ApiException.badRequest("不允许操作系统表：" + table);
        }
        if (!connection.tableExists(table)) {
            throw ApiException.notFound("表不存在：" + table);
        }
        JobHandle handle = registry.create("TRUNCATE", clusterId, table);
        registry.submit(handle, () -> run(handle));
        return handle.toView();
    }

    private void run(JobHandle handle) {
        String tableName = handle.table();
        handle.markStarted();
        int batchSize = Math.max(1, props.getTruncate().getBatchSize());
        int maxRows = Math.max(1, props.getTruncate().getMaxRows());

        KuduScanner scanner = null;
        KuduSession session = null;
        long processed = 0;
        long deleted = 0;
        long failed = 0;
        try {
            KuduTable table = connection.table(tableName);
            Schema schema = table.getSchema();
            List<ColumnSchema> primaryKey = schema.getPrimaryKeyColumns();
            List<String> pkNames = primaryKey.stream().map(ColumnSchema::getName).toList();

            session = connection.client().newSession();
            session.setFlushMode(SessionConfiguration.FlushMode.MANUAL_FLUSH);
            session.setMutationBufferSpace(batchSize);
            session.setTimeoutMillis(props.getOperationTimeoutMs());

            scanner = connection.client().newScannerBuilder(table)
                    .setProjectedColumnNames(pkNames)
                    .cacheBlocks(false)
                    .batchSizeBytes(props.getQuery().getBatchBytes())
                    .readMode(AsyncKuduScanner.ReadMode.READ_LATEST)
                    .limit(maxRows + 1L)
                    .build();

            int pending = 0;
            outer:
            while (scanner.hasMoreRows()) {
                for (RowResult row : scanner.nextRows()) {
                    if (handle.cancelRequested()) {
                        break outer;
                    }
                    Operation delete = table.newDelete();
                    for (ColumnSchema column : primaryKey) {
                        KuduValueConverter.copyValue(row, delete.getRow(), column);
                    }
                    session.apply(delete);
                    processed++;
                    pending++;
                    if (processed % 200 == 0) {
                        handle.processed().set(processed);
                    }
                    if (pending >= batchSize) {
                        long[] result = flush(session);
                        deleted += result[0];
                        failed += result[1];
                        pending = 0;
                        handle.processed().set(processed);
                        handle.deleted().set(deleted);
                        handle.failed().set(failed);
                    }
                    if (processed > maxRows) {
                        throw new IllegalStateException("表行数超过清空上限 " + maxRows
                                + "，请改用 DROP TABLE 或分批删除");
                    }
                }
            }
            long[] result = flush(session);
            deleted += result[0];
            failed += result[1];
            handle.processed().set(processed);
            handle.deleted().set(deleted);
            handle.failed().set(failed);

            if (handle.cancelRequested()) {
                handle.markFinished("CANCELED", "已取消，已删除 " + deleted + " 行");
            } else if (failed > 0) {
                handle.markFinished("FAILED", "完成但有 " + failed + " 行删除失败");
            } else {
                handle.markFinished("SUCCEEDED", "已删除 " + deleted + " 行");
            }
            log.info("truncate done: table={}, processed={}, deleted={}, failed={}",
                    tableName, processed, deleted, failed);
        } catch (Throwable e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            handle.markFinished("FAILED", message);
            log.warn("truncate failed: table={}, processed={}, reason={}", tableName, processed, message);
        } finally {
            if (scanner != null) {
                try {
                    scanner.close();
                } catch (Exception ignored) {
                    // 忽略关闭异常
                }
            }
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignored) {
                    // 忽略关闭异常
                }
            }
            handle.processed().set(processed);
            handle.deleted().set(deleted);
            handle.failed().set(failed);
        }
    }

    /** @return [成功数, 失败数]；行已不存在视为成功。 */
    private static long[] flush(KuduSession session) {
        long ok = 0;
        long fail = 0;
        List<OperationResponse> responses;
        try {
            responses = session.flush();
        } catch (Exception e) {
            return new long[]{0, 0};
        }
        if (responses == null) {
            return new long[]{0, 0};
        }
        for (OperationResponse response : responses) {
            if (response != null && response.hasRowError()) {
                String text = String.valueOf(response.getRowError());
                if (text.toLowerCase().contains("not found")) {
                    ok++; // 并发删除，目标状态已达成
                } else {
                    fail++;
                }
            } else {
                ok++;
            }
        }
        return new long[]{ok, fail};
    }
}
