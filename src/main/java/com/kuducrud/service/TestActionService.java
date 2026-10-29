package com.kuducrud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kuducrud.config.KuduProperties;
import com.kuducrud.job.JobHandle;
import com.kuducrud.job.JobRegistry;
import com.kuducrud.kudu.KuduConnectionManager;
import com.kuducrud.kudu.KuduErrors;
import com.kuducrud.kudu.KuduSessionTemplate;
import com.kuducrud.kudu.KuduValueConverter;
import com.kuducrud.kudu.TableNameUtil;
import com.kuducrud.kudu.TablePrefixMatcher;
import com.kuducrud.kudu.TestRowGenerator;
import com.kuducrud.model.ColumnDef;
import com.kuducrud.model.JobView;
import com.kuducrud.model.MutationResult;
import com.kuducrud.model.TestActionSpec;
import com.kuducrud.model.TestTableResult;
import com.kuducrud.web.ApiException;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Schema;
import org.apache.kudu.client.AsyncKuduScanner;
import org.apache.kudu.client.KuduScanner;
import org.apache.kudu.client.KuduTable;
import org.apache.kudu.client.RowResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 备份测试动作：删除数据 → 修改表结构（加列）→ 添加数据。
 * <p>按表执行，单表失败不影响其他表；执行期间占用这些表的表级锁，
 * 避免与 truncate / 删表 / 删库并发。删除先于新增，避免把刚插入的行删掉。
 */
public class TestActionService {

    private static final Logger log = LoggerFactory.getLogger(TestActionService.class);
    private static final int DELETE_SCAN_LIMIT = 5_000;
    private static final String TEST_COLUMN_PREFIX = "bk_test_";
    private static final String JOB_TYPE = "TEST_MUTATE";

    private final KuduConnectionManager connection;
    private final DdlService ddl;
    private final KuduSessionTemplate session;
    private final JobRegistry jobs;
    private final KuduProperties props;
    private final ObjectMapper mapper;
    private final String clusterId;
    /** 手动执行也记住上一轮加的测试列，REPLACE 模式据此先删旧列。重启后丢失，可接受。 */
    private final Map<String, String> lastTestColumns = new java.util.concurrent.ConcurrentHashMap<>();

    public TestActionService(KuduConnectionManager connection, DdlService ddl, KuduSessionTemplate session,
                             JobRegistry jobs, KuduProperties props, ObjectMapper mapper, String clusterId) {
        this.connection = connection;
        this.ddl = ddl;
        this.session = session;
        this.jobs = jobs;
        this.props = props;
        this.mapper = mapper;
        this.clusterId = clusterId;
    }

    public record RunResult(List<TestTableResult> tables, Map<String, String> lastColumns,
                            int succeeded, int failed) {
    }

    private record DeleteResult(int applied, int failed, String note) {
    }

    private record AddResult(int applied, int failed, String note) {
    }

    /** 手动立即执行：一次性对多张表执行选中的动作，返回任务视图。 */
    public JobView submit(List<String> tables, TestActionSpec actions) {
        List<String> targets = TestActionRules.normalizeTables(tables);
        TestActionSpec spec = TestActionRules.normalize(actions);
        TestActionRules.validate(spec);
        checkConflicts(targets);

        String target = targets.size() == 1 ? targets.get(0) : targets.size() + " 张表";
        JobHandle handle = jobs.createBatch(JOB_TYPE, clusterId, target, targets, "测试操作");
        handle.totalEstimate().set((long) targets.size() * spec.enabledActions());
        jobs.submit(handle, () -> execute(handle, targets, spec, lastTestColumns));
        log.info("test action submitted: tables={}, actions={}", targets.size(), spec.enabledActions());
        return handle.toView();
    }

    /** 执行一批表；供手动任务和定时调度共用。 */
    public RunResult execute(JobHandle handle, List<String> tables, TestActionSpec actions,
                             Map<String, String> lastColumns) {
        TestActionSpec spec = TestActionRules.normalize(actions);
        handle.markStarted();
        List<TestTableResult> results = new ArrayList<>();
        Map<String, String> columns = new HashMap<>();
        int succeeded = 0;
        int failed = 0;
        try {
            for (String table : tables) {
                if (handle.cancelRequested()) {
                    break;
                }
                long before = handle.processed().get();
                try {
                    TestTableResult result = runTable(handle, table, spec,
                            lastColumns == null ? null : lastColumns.get(table), columns);
                    results.add(result);
                    if (result.failed() == 0) {
                        succeeded++;
                    } else {
                        failed++;
                    }
                } catch (Exception e) {
                    failed++;
                    String message = messageOf(e);
                    results.add(new TestTableResult(table, 0, 0, null, 1, message));
                    handle.failed().incrementAndGet();
                    long expected = spec.enabledActions();
                    long done = handle.processed().get() - before;
                    if (done < expected) {
                        handle.processed().addAndGet(expected - done);
                    }
                    log.warn("test action failed: table={}, reason={}", table, message);
                }
            }
            handle.markFinished(handle.cancelRequested() ? "CANCELED" : failed > 0 ? "FAILED" : "SUCCEEDED",
                    summary(tables, results, handle.cancelRequested()));
            lastTestColumns.putAll(columns);
            log.info("test action done: tables={}, succeeded={}, failed={}", tables.size(), succeeded, failed);
            return new RunResult(List.copyOf(results), Map.copyOf(columns), succeeded, failed);
        } catch (Throwable e) {
            String message = messageOf(e);
            handle.markFinished("FAILED", "测试操作中断：" + message);
            log.warn("test action aborted: reason={}", message);
            return new RunResult(List.copyOf(results), Map.copyOf(columns), succeeded, failed);
        }
    }

    /** 提交前检查是否有删库 / 按前缀删表任务覆盖了目标表。 */
    public void checkConflicts(List<String> tables) {
        for (String key : jobs.runningResourceKeys(clusterId)) {
            if (key.startsWith(JobRegistry.DB_KEY)) {
                String db = key.substring(JobRegistry.DB_KEY.length());
                for (String table : tables) {
                    if (db.equalsIgnoreCase(TableNameUtil.dbOf(table))) {
                        throw ApiException.conflict("JOB_CONFLICT",
                                "库 " + db + " 正在执行删除任务，请等待其结束");
                    }
                }
            } else if (key.startsWith(JobRegistry.PREFIX_KEY)) {
                String prefix = key.substring(JobRegistry.PREFIX_KEY.length());
                for (String table : tables) {
                    if (TablePrefixMatcher.matches(prefix, table)) {
                        throw ApiException.conflict("JOB_CONFLICT",
                                "前缀 " + prefix + " 正在执行删除任务，请等待其结束");
                    }
                }
            }
        }
    }

    private TestTableResult runTable(JobHandle handle, String table, TestActionSpec spec,
                                 String previousColumn, Map<String, String> columns) {
        if (TableNameUtil.isSystemTable(table)) {
            throw ApiException.badRequest("不允许操作 Kudu 系统表：" + table);
        }
        connection.table(table); // 确认表存在且可打开

        int deleted = 0;
        int added = 0;
        int failed = 0;
        String newColumn = null;
        List<String> notes = new ArrayList<>();

        if (spec.deleteRows() && !handle.cancelRequested()) {
            try {
                DeleteResult result = deleteRows(table, spec.deleteCount());
                deleted += result.applied();
                failed += result.failed();
                handle.deleted().addAndGet(result.applied());
                handle.failed().addAndGet(result.failed());
                if (result.note() != null) {
                    notes.add(result.note());
                }
            } catch (Exception e) {
                failed++;
                handle.failed().incrementAndGet();
                notes.add("删除失败：" + messageOf(e));
            } finally {
                handle.processed().incrementAndGet();
            }
        }

        if (spec.addColumn() && !handle.cancelRequested()) {
            try {
                newColumn = addTestColumn(table, previousColumn, spec);
                columns.put(table, newColumn);
            } catch (Exception e) {
                failed++;
                handle.failed().incrementAndGet();
                notes.add("加列失败：" + messageOf(e));
            } finally {
                handle.processed().incrementAndGet();
            }
        }

        if (spec.addRows() && !handle.cancelRequested()) {
            try {
                AddResult result = addRows(table, spec.addCount());
                added += result.applied();
                failed += result.failed();
                handle.failed().addAndGet(result.failed());
                if (result.note() != null) {
                    notes.add(result.note());
                }
            } catch (Exception e) {
                failed++;
                handle.failed().incrementAndGet();
                notes.add("新增失败：" + messageOf(e));
            } finally {
                handle.processed().incrementAndGet();
            }
        }

        String message = notes.isEmpty() ? null : String.join("；", notes);
        return new TestTableResult(table, deleted, added, newColumn, failed, message);
    }

    private DeleteResult deleteRows(String table, int count) {
        KuduTable kuduTable = connection.table(table);
        int limit = Math.min(DELETE_SCAN_LIMIT, Math.max(1, props.getQuery().getMaxScanRows()));
        List<Map<String, JsonNode>> keys = scanKeys(kuduTable, limit);
        if (keys.isEmpty()) {
            return new DeleteResult(0, 0, "表内没有可删除的行");
        }
        Collections.shuffle(keys, ThreadLocalRandom.current());
        List<Map<String, JsonNode>> target = new ArrayList<>(
                keys.subList(0, Math.min(count, keys.size())));
        MutationResult result = session.apply(table, "DELETE", target, false);
        String note = target.size() < count ? "表内只扫描到 " + target.size() + " 行，实际删除 " + result.applied() + " 行" : null;
        if (result.failed() > 0) {
            note = join(note, errorText(result));
        }
        return new DeleteResult(result.applied(), result.failed(), note);
    }

    private AddResult addRows(String table, int count) {
        KuduTable kuduTable = connection.table(table);
        Schema schema = kuduTable.getSchema();
        int scanLimit = Math.max(1, props.getQuery().getMaxScanRows());
        List<Map<String, JsonNode>> existing = scanKeys(kuduTable, scanLimit);
        long seed = System.currentTimeMillis();
        List<Map<String, JsonNode>> rows = TestRowGenerator.rows(schema, count, seed, existing);
        MutationResult result = session.apply(table, "INSERT", rows, false);
        if (result.applied() == 0 && result.failed() > 0) {
            // 主键冲突时换一个种子再试一次；整型主键按最大值递增时通常已经避让
            rows = TestRowGenerator.rows(schema, count, seed + 1_000_003L, existing);
            result = session.apply(table, "INSERT", rows, false);
        }
        String note = result.failed() > 0 ? errorText(result) : null;
        return new AddResult(result.applied(), result.failed(), note);
    }

    private String addTestColumn(String table, String previousColumn, TestActionSpec spec) {
        if (TestActionSpec.MODE_REPLACE.equals(spec.columnMode())
                && previousColumn != null && previousColumn.startsWith(TEST_COLUMN_PREFIX)) {
            KuduTable kuduTable = connection.table(table);
            if (kuduTable.getSchema().getColumn(previousColumn) != null) {
                ddl.dropColumn(table, previousColumn);
                log.info("previous test column dropped: {}.{}", table, previousColumn);
            }
        }
        String column = newColumnName();
        ddl.addColumn(table, new ColumnDef(column, spec.columnType(), false, true,
                null, null, null, null, null, null, null, "备份测试自动添加的列"));
        log.info("test column added: {}.{}", table, column);
        return column;
    }

    private static String newColumnName() {
        return TEST_COLUMN_PREFIX + System.currentTimeMillis() + "_"
                + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000));
    }

    /** 只扫描主键列；limit 控制扫描量，避免在大表上拖垮 tserver。 */
    private List<Map<String, JsonNode>> scanKeys(KuduTable table, int limit) {
        Schema schema = table.getSchema();
        List<String> keyNames = schema.getPrimaryKeyColumns().stream().map(ColumnSchema::getName).toList();
        List<Map<String, JsonNode>> rows = new ArrayList<>();
        KuduScanner scanner = null;
        try {
            scanner = connection.client().newScannerBuilder(table)
                    .setProjectedColumnNames(keyNames)
                    .cacheBlocks(false)
                    .readMode(AsyncKuduScanner.ReadMode.READ_LATEST)
                    .limit(limit)
                    .build();
            while (scanner.hasMoreRows() && rows.size() < limit) {
                for (RowResult row : scanner.nextRows()) {
                    Map<String, JsonNode> values = new LinkedHashMap<>();
                    for (ColumnSchema key : schema.getPrimaryKeyColumns()) {
                        Object value = KuduValueConverter.read(row, key);
                        values.put(key.getName(), value == null
                                ? mapper.getNodeFactory().nullNode() : mapper.valueToTree(value));
                    }
                    rows.add(values);
                    if (rows.size() >= limit) {
                        break;
                    }
                }
            }
            return rows;
        } catch (Exception e) {
            throw translate(e, "扫描表 " + table.getName() + " 主键失败");
        } finally {
            if (scanner != null) {
                try {
                    scanner.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响结果
                }
            }
        }
    }

    private static String summary(List<String> tables, List<TestTableResult> results, boolean canceled) {
        int ok = (int) results.stream().filter(r -> r.failed() == 0).count();
        String head = canceled ? "已取消，" : "";
        head += "完成 " + ok + "/" + tables.size() + " 张表";
        if (results.isEmpty()) {
            return head;
        }
        String detail = results.stream()
                .map(r -> r.table() + "（删 " + r.deleted() + " / 增 " + r.added()
                        + (r.column() == null ? "" : " / 加列 " + r.column())
                        + (r.failed() > 0 ? " / 失败 " + r.failed() : "") + "）")
                .collect(Collectors.joining("，"));
        String message = head + "：" + detail;
        return message.length() <= 1200 ? message : message.substring(0, 1200) + "…";
    }

    private static String errorText(MutationResult result) {
        return result.errors().stream()
                .limit(2)
                .map(e -> "行 " + e.rowKey() + "：" + e.message())
                .collect(Collectors.joining("；"));
    }

    private static String join(String first, String second) {
        if (first == null || first.isBlank()) {
            return second;
        }
        return second == null || second.isBlank() ? first : first + "；" + second;
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private static ApiException translate(Exception e, String context) {
        ApiException api = KuduErrors.translate(e, context);
        if (api != null) {
            return api;
        }
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL",
                context + "：" + messageOf(e), e);
    }
}
