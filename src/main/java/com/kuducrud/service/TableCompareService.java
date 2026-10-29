package com.kuducrud.service;

import com.kuducrud.config.ClusterRegistry;
import com.kuducrud.config.ClusterRuntime;
import com.kuducrud.kudu.KuduConnectionManager;
import com.kuducrud.kudu.TableNameUtil;
import com.kuducrud.model.CompareTaskView;
import com.kuducrud.model.TableCompareRequest;
import com.kuducrud.model.TableCompareResult;
import com.kuducrud.model.TableCompareResult.ColumnDiff;
import com.kuducrud.model.TableCompareResult.DataDiff;
import com.kuducrud.model.TableCompareResult.DiffSample;
import com.kuducrud.model.TableCompareResult.TableSide;
import com.kuducrud.web.ApiException;
import jakarta.annotation.PreDestroy;
import org.apache.kudu.Schema;
import org.apache.kudu.client.AsyncKuduScanner;
import org.apache.kudu.client.KuduScanner;
import org.apache.kudu.client.KuduTable;
import org.apache.kudu.client.RowResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 表比对：表存在性 → schema → 全量行内容对账，支持同平台或跨平台两张表。
 * <p>口径参考 KuduTableCompareIT：源表整表扫进内存（主键 → 整行编码），再扫目标表逐行对账，
 * 报目标缺失 / 目标多出 / 内容不一致三类差异；schema 按列名排序比对，忽略列顺序、encoding、compression。
 * <p>只读比对，不占用表锁；执行期间应避免写入，否则结果可能自相矛盾。异步执行，前端轮询进度。
 */
@Service
public class TableCompareService {

    private static final Logger log = LoggerFactory.getLogger(TableCompareService.class);
    private static final int SCAN_BATCH_BYTES = 4 * 1024 * 1024;
    private static final int DEFAULT_MAX_ROWS = 200_000;
    private static final int MAX_ROWS_LIMIT = 1_000_000;
    private static final int DEFAULT_SAMPLE_LIMIT = 10;
    private static final int MAX_SAMPLE_LIMIT = 50;
    private static final int MAX_TASKS = 20;

    private final ClusterRegistry clusters;
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    public TableCompareService(ClusterRegistry clusters) {
        this.clusters = clusters;
        AtomicInteger sequence = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "kudu-compare-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public CompareTaskView submit(TableCompareRequest request) {
        Validated spec = validate(request);
        Task task = new Task(newId(), spec);
        tasks.put(task.id, task);
        trim();
        try {
            executor.submit(() -> run(task));
        } catch (RuntimeException e) {
            task.state = "FAILED";
            task.message = "无法启动比对任务：" + messageOf(e);
            task.finishedAtMs = System.currentTimeMillis();
        }
        return task.view();
    }

    public CompareTaskView get(String id) {
        return task(id).view();
    }

    public CompareTaskView cancel(String id) {
        Task task = task(id);
        task.canceled.set(true);
        return task.view();
    }

    private Task task(String id) {
        Task task = tasks.get(id);
        if (task == null) {
            throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
                    "COMPARE_NOT_FOUND", "比对任务不存在：" + id);
        }
        return task;
    }

    private void run(Task task) {
        Validated spec = task.spec;
        task.state = "RUNNING";
        try {
            if (task.canceled.get()) {
                finishCanceled(task);
                return;
            }
            ClusterRuntime leftRuntime = clusters.runtime(spec.leftCluster());
            ClusterRuntime rightRuntime = clusters.runtime(spec.rightCluster());
            KuduConnectionManager leftConnection = leftRuntime.connection();
            KuduConnectionManager rightConnection = rightRuntime.connection();
            String leftClusterName = leftRuntime.config().name();
            String rightClusterName = rightRuntime.config().name();

            boolean leftExists = leftConnection.tableExists(spec.leftTable());
            boolean rightExists = rightConnection.tableExists(spec.rightTable());
            if (!leftExists || !rightExists) {
                String message = !leftExists && !rightExists
                        ? "源表和目标表都不存在"
                        : !leftExists ? "源表不存在：" + spec.leftTable() : "目标表不存在：" + spec.rightTable();
                task.result = result(false, message,
                        new TableSide(spec.leftCluster(), leftClusterName, spec.leftTable(), leftExists, 0, null),
                        new TableSide(spec.rightCluster(), rightClusterName, spec.rightTable(), rightExists, 0, null),
                        false, List.of(), List.of(), List.of(), false, List.of(), List.of(), null, task.startedAtMs);
                task.state = "SUCCEEDED";
                task.message = message;
                task.finishedAtMs = System.currentTimeMillis();
                return;
            }

            KuduTable leftTable = leftConnection.table(spec.leftTable());
            KuduTable rightTable = rightConnection.table(spec.rightTable());
            Schema leftSchema = leftTable.getSchema();
            Schema rightSchema = rightTable.getSchema();
            Map<String, String> leftSignature = TableCompareSupport.signatureMap(leftSchema);
            Map<String, String> rightSignature = TableCompareSupport.signatureMap(rightSchema);
            boolean columnsEqual = leftSignature.equals(rightSignature);
            List<ColumnDiff> columnDiffs = diffColumns(leftSignature, rightSignature);
            List<String> leftPrimaryKey = TableCompareSupport.primaryKeyNames(leftSchema);
            List<String> rightPrimaryKey = TableCompareSupport.primaryKeyNames(rightSchema);
            boolean primaryKeyEqual = leftPrimaryKey.equals(rightPrimaryKey);

            TableSide leftSide = new TableSide(spec.leftCluster(), leftClusterName, spec.leftTable(),
                    true, leftSchema.getColumnCount(), null);
            TableSide rightSide = new TableSide(spec.rightCluster(), rightClusterName, spec.rightTable(),
                    true, rightSchema.getColumnCount(), null);

            if (!columnsEqual) {
                String message = "表结构不一致（" + columnDiffs.size() + " 处差异），已跳过数据比对";
                task.result = result(false, message, leftSide, rightSide, false,
                        TableCompareSupport.schemaSignature(leftSchema),
                        TableCompareSupport.schemaSignature(rightSchema),
                        columnDiffs, primaryKeyEqual, leftPrimaryKey, rightPrimaryKey, null, task.startedAtMs);
                task.state = "SUCCEEDED";
                task.message = message;
                task.finishedAtMs = System.currentTimeMillis();
                return;
            }

            if (!spec.compareData()) {
                String message = primaryKeyEqual
                        ? "表结构一致（未比对数据）" : "表结构一致，但主键顺序不一致";
                task.result = result(primaryKeyEqual, message, leftSide, rightSide, true,
                        TableCompareSupport.schemaSignature(leftSchema),
                        TableCompareSupport.schemaSignature(rightSchema),
                        columnDiffs, primaryKeyEqual, leftPrimaryKey, rightPrimaryKey, null, task.startedAtMs);
                task.state = "SUCCEEDED";
                task.message = message;
                task.finishedAtMs = System.currentTimeMillis();
                return;
            }

            Map<String, String> leftRows = new HashMap<>();
            long leftCount = scanLeft(task, leftConnection, leftTable, leftSchema, leftRows);
            if (task.canceled.get()) {
                finishCanceled(task);
                return;
            }

            DataAccumulator data = scanRight(task, rightConnection, rightTable, rightSchema, leftRows);
            if (task.canceled.get()) {
                finishCanceled(task);
                return;
            }

            long missing = leftRows.size();
            data.collectMissingSamples(leftRows);
            boolean dataEqual = missing == 0 && data.extra == 0 && data.mismatch == 0;
            boolean consistent = primaryKeyEqual && dataEqual;
            String dataMessage = "目标缺失 " + missing + " 行，多出 " + data.extra
                    + " 行，内容不一致 " + data.mismatch + " 行";
            String message;
            if (!primaryKeyEqual && !dataEqual) {
                message = "主键顺序不一致；数据不一致：" + dataMessage;
            } else if (!primaryKeyEqual) {
                message = "主键顺序不一致";
            } else if (!dataEqual) {
                message = "数据不一致：" + dataMessage;
            } else {
                message = leftCount + " 行数据一致";
            }

            task.result = result(consistent, message,
                    new TableSide(spec.leftCluster(), leftClusterName, spec.leftTable(), true,
                            leftSchema.getColumnCount(), leftCount),
                    new TableSide(spec.rightCluster(), rightClusterName, spec.rightTable(), true,
                            rightSchema.getColumnCount(), data.rightCount),
                    true, TableCompareSupport.schemaSignature(leftSchema),
                    TableCompareSupport.schemaSignature(rightSchema),
                    columnDiffs, primaryKeyEqual, leftPrimaryKey, rightPrimaryKey,
                    new DataDiff(data.matched, missing, data.extra, data.mismatch,
                            data.missingSamples, data.extraSamples, data.mismatchSamples),
                    task.startedAtMs);
            task.state = "SUCCEEDED";
            task.message = message;
            task.finishedAtMs = System.currentTimeMillis();
            log.info("table compare done: {} -> {}, consistent={}", spec.leftTable(), spec.rightTable(), consistent);
        } catch (Exception e) {
            if (task.canceled.get()) {
                finishCanceled(task);
                return;
            }
            task.state = "FAILED";
            task.message = messageOf(e);
            task.finishedAtMs = System.currentTimeMillis();
            log.warn("table compare failed: {} -> {}, reason={}",
                    spec.leftTable(), spec.rightTable(), task.message);
        }
    }

    private static long scanLeft(Task task, KuduConnectionManager connection, KuduTable table,
                                 Schema schema, Map<String, String> rows) throws Exception {
        KuduScanner scanner = null;
        long count = 0;
        try {
            scanner = scanner(connection, table);
            while (scanner.hasMoreRows()) {
                for (RowResult row : scanner.nextRows()) {
                    if (task.canceled.get()) {
                        return count;
                    }
                    count++;
                    if (count > task.spec.maxRows()) {
                        throw ApiException.badRequest("源表行数超过上限 " + task.spec.maxRows()
                                + "，请调大最大扫描行数或先缩小数据范围");
                    }
                    rows.put(TableCompareSupport.canonicalKey(row, schema),
                            TableCompareSupport.canonicalRow(row, schema));
                }
                task.leftScanned.set(count);
            }
            return count;
        } finally {
            task.leftScanned.set(count);
            close(scanner);
        }
    }

    private static DataAccumulator scanRight(Task task, KuduConnectionManager connection, KuduTable table,
                                             Schema schema, Map<String, String> leftRows) throws Exception {
        DataAccumulator data = new DataAccumulator(task.spec.sampleLimit());
        KuduScanner scanner = null;
        long count = 0;
        try {
            scanner = scanner(connection, table);
            while (scanner.hasMoreRows()) {
                for (RowResult row : scanner.nextRows()) {
                    if (task.canceled.get()) {
                        return data;
                    }
                    count++;
                    if (count > task.spec.maxRows()) {
                        throw ApiException.badRequest("目标表行数超过上限 " + task.spec.maxRows()
                                + "，请调大最大扫描行数或先缩小数据范围");
                    }
                    String key = TableCompareSupport.canonicalKey(row, schema);
                    String targetRow = TableCompareSupport.canonicalRow(row, schema);
                    String sourceRow = leftRows.remove(key);
                    if (sourceRow == null) {
                        data.extra++;
                        data.addExtra(key, targetRow);
                    } else if (!sourceRow.equals(targetRow)) {
                        data.mismatch++;
                        data.addMismatch(key, sourceRow, targetRow);
                    } else {
                        data.matched++;
                    }
                }
                task.rightScanned.set(count);
            }
            data.rightCount = count;
            return data;
        } finally {
            task.rightScanned.set(count);
            close(scanner);
        }
    }

    private static KuduScanner scanner(KuduConnectionManager connection, KuduTable table) {
        return connection.client().newScannerBuilder(table)
                .batchSizeBytes(SCAN_BATCH_BYTES)
                .cacheBlocks(false)
                .readMode(AsyncKuduScanner.ReadMode.READ_LATEST)
                .build();
    }

    private static void close(KuduScanner scanner) {
        if (scanner == null) {
            return;
        }
        try {
            scanner.close();
        } catch (Exception ignored) {
            // 关闭失败不影响比对结果
        }
    }

    private static void finishCanceled(Task task) {
        task.state = "CANCELED";
        task.message = "已取消（源已扫描 " + task.leftScanned.get() + " 行，目标已扫描 "
                + task.rightScanned.get() + " 行）";
        task.finishedAtMs = System.currentTimeMillis();
    }

    private static TableCompareResult result(boolean consistent, String message, TableSide left, TableSide right,
                                             boolean schemaEqual, List<String> leftSchema, List<String> rightSchema,
                                             List<ColumnDiff> diffs, boolean primaryKeyEqual,
                                             List<String> leftPrimaryKey, List<String> rightPrimaryKey,
                                             DataDiff data, long startedAtMs) {
        return new TableCompareResult(consistent, message, left, right, schemaEqual,
                leftSchema, rightSchema, diffs, primaryKeyEqual, leftPrimaryKey, rightPrimaryKey,
                data, System.currentTimeMillis() - startedAtMs);
    }

    private static List<ColumnDiff> diffColumns(Map<String, String> left, Map<String, String> right) {
        List<ColumnDiff> diffs = new ArrayList<>();
        for (Map.Entry<String, String> entry : left.entrySet()) {
            String other = right.get(entry.getKey());
            if (other == null) {
                diffs.add(new ColumnDiff("MISSING_IN_RIGHT", entry.getKey(), entry.getValue(), null));
            } else if (!other.equals(entry.getValue())) {
                diffs.add(new ColumnDiff("CHANGED", entry.getKey(), entry.getValue(), other));
            }
        }
        for (Map.Entry<String, String> entry : right.entrySet()) {
            if (!left.containsKey(entry.getKey())) {
                diffs.add(new ColumnDiff("EXTRA_IN_RIGHT", entry.getKey(), null, entry.getValue()));
            }
        }
        diffs.sort(Comparator.comparing(ColumnDiff::name));
        return diffs;
    }

    private void trim() {
        if (tasks.size() <= MAX_TASKS) {
            return;
        }
        List<Task> finished = tasks.values().stream()
                .filter(task -> task.finishedAtMs != null)
                .sorted(Comparator.comparingLong(task -> task.startedAtMs))
                .toList();
        int remove = tasks.size() - MAX_TASKS;
        for (int i = 0; i < Math.min(remove, finished.size()); i++) {
            tasks.remove(finished.get(i).id);
        }
    }

    private Validated validate(TableCompareRequest request) {
        if (request == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        String leftTable = requireTable(request.leftTable(), "源表");
        String rightTable = requireTable(request.rightTable(), "目标表");
        String leftCluster = resolveCluster(request.leftCluster());
        String rightCluster = resolveCluster(request.rightCluster());
        if (leftCluster.equals(rightCluster) && leftTable.equals(rightTable)) {
            throw ApiException.badRequest("源表和目标表相同，无需比对");
        }
        int maxRows = request.maxRows() == null ? DEFAULT_MAX_ROWS : request.maxRows();
        if (maxRows < 1 || maxRows > MAX_ROWS_LIMIT) {
            throw ApiException.badRequest("最大扫描行数需在 1.." + MAX_ROWS_LIMIT);
        }
        int sampleLimit = request.sampleLimit() == null ? DEFAULT_SAMPLE_LIMIT : request.sampleLimit();
        if (sampleLimit < 0 || sampleLimit > MAX_SAMPLE_LIMIT) {
            throw ApiException.badRequest("差异样本数需在 0.." + MAX_SAMPLE_LIMIT);
        }
        boolean compareData = request.compareData() == null || request.compareData();
        return new Validated(leftCluster, leftTable, rightCluster, rightTable, compareData, maxRows, sampleLimit);
    }

    private String resolveCluster(String id) {
        String resolved = id == null || id.isBlank() ? clusters.activeId() : id.trim();
        clusters.config(resolved);
        return resolved;
    }

    private static String requireTable(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest(label + "不能为空");
        }
        String table = raw.trim();
        if (TableNameUtil.isSystemTable(table)) {
            throw ApiException.badRequest("不支持比对系统表：" + table);
        }
        return table;
    }

    private static String newId() {
        return "cmp" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private record Validated(String leftCluster, String leftTable, String rightCluster, String rightTable,
                             boolean compareData, int maxRows, int sampleLimit) {
    }

    private static final class Task {
        private final String id;
        private final Validated spec;
        private volatile String state = "PENDING";
        private volatile String message;
        private final long startedAtMs = System.currentTimeMillis();
        private volatile Long finishedAtMs;
        private final AtomicLong leftScanned = new AtomicLong();
        private final AtomicLong rightScanned = new AtomicLong();
        private final AtomicBoolean canceled = new AtomicBoolean(false);
        private volatile TableCompareResult result;

        private Task(String id, Validated spec) {
            this.id = id;
            this.spec = spec;
        }

        private CompareTaskView view() {
            return new CompareTaskView(id, state, message, leftScanned.get(), rightScanned.get(),
                    startedAtMs, finishedAtMs, result);
        }
    }

    private static final class DataAccumulator {
        private final int sampleLimit;
        private long matched;
        private long extra;
        private long mismatch;
        private long rightCount;
        private final List<DiffSample> missingSamples = new ArrayList<>();
        private final List<DiffSample> extraSamples = new ArrayList<>();
        private final List<DiffSample> mismatchSamples = new ArrayList<>();

        private DataAccumulator(int sampleLimit) {
            this.sampleLimit = sampleLimit;
        }

        private void addExtra(String key, String rightRow) {
            if (extraSamples.size() < sampleLimit) {
                extraSamples.add(new DiffSample(key, null, TableCompareSupport.abbrev(rightRow)));
            }
        }

        private void addMismatch(String key, String leftRow, String rightRow) {
            if (mismatchSamples.size() < sampleLimit) {
                mismatchSamples.add(new DiffSample(key, TableCompareSupport.abbrev(leftRow),
                        TableCompareSupport.abbrev(rightRow)));
            }
        }

        private void collectMissingSamples(Map<String, String> leftover) {
            if (sampleLimit == 0) {
                return;
            }
            for (Map.Entry<String, String> entry : leftover.entrySet()) {
                if (missingSamples.size() >= sampleLimit) {
                    break;
                }
                missingSamples.add(new DiffSample(entry.getKey(),
                        TableCompareSupport.abbrev(entry.getValue()), null));
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
