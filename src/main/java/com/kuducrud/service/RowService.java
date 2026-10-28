package com.kuducrud.service;

import com.kuducrud.config.KuduProperties;
import com.kuducrud.kudu.KuduConnectionManager;
import com.kuducrud.kudu.KuduErrors;
import com.kuducrud.kudu.KuduSessionTemplate;
import com.kuducrud.kudu.KuduValueConverter;
import com.kuducrud.kudu.PredicateBuilder;
import com.kuducrud.kudu.SchemaMapper;
import com.kuducrud.model.ColumnDef;
import com.kuducrud.model.FilterSpec;
import com.kuducrud.model.MutationRequest;
import com.kuducrud.model.MutationResult;
import com.kuducrud.model.QueryRequest;
import com.kuducrud.model.QueryResponse;
import com.kuducrud.web.ApiException;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Schema;
import org.apache.kudu.client.AsyncKuduScanner;
import org.apache.kudu.client.KuduScanner;
import org.apache.kudu.client.KuduTable;
import org.apache.kudu.client.RowResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 行级读写。
 * <p>分页采用「扫描 + 跳过 offset」：Kudu 没有 OFFSET，hash 分区下扫描也不保证主键有序，
 * 因此不能用主键游标翻页；用 limit+offset+1 的硬上限保证不会无界扫描。
 */
public class RowService {

    private final KuduConnectionManager connection;
    private final KuduSessionTemplate sessionTemplate;
    private final KuduProperties props;

    public RowService(KuduConnectionManager connection, KuduSessionTemplate sessionTemplate, KuduProperties props) {
        this.connection = connection;
        this.sessionTemplate = sessionTemplate;
        this.props = props;
    }

    public QueryResponse query(String tableName, QueryRequest request) {
        long started = System.currentTimeMillis();
        KuduTable table = connection.table(tableName);
        Schema schema = table.getSchema();

        int limit = request == null || request.limit() == null
                ? props.getQuery().getDefaultLimit() : request.limit();
        if (limit < 1 || limit > props.getQuery().getMaxLimit()) {
            throw ApiException.badRequest("limit 需在 1.." + props.getQuery().getMaxLimit());
        }
        int offset = request == null || request.offset() == null ? 0 : request.offset();
        if (offset < 0) {
            throw ApiException.badRequest("offset 不能为负");
        }
        if (offset > props.getQuery().getMaxOffset()) {
            throw ApiException.badRequest("offset 上限为 " + props.getQuery().getMaxOffset()
                    + "，请增加过滤条件缩小范围");
        }

        List<FilterSpec> filters = request == null ? List.of() : request.filters();
        PredicateBuilder.Plan plan = PredicateBuilder.build(schema, filters);

        List<String> projection = resolveProjection(schema, request == null ? null : request.columns(), plan);

        long scanLimit;
        if (plan.clientFiltered()) {
            scanLimit = props.getQuery().getMaxScanRows();
        } else {
            scanLimit = (long) offset + limit + 1;
            if (scanLimit > props.getQuery().getMaxScanRows()) {
                throw ApiException.badRequest("offset + limit 超过单次扫描上限 "
                        + props.getQuery().getMaxScanRows() + "，请增加过滤条件");
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>(limit);
        int skipped = 0;
        int scanned = 0;
        boolean hasNext = false;
        boolean truncated = false;

        KuduScanner scanner = null;
        try {
            var builder = connection.client().newScannerBuilder(table)
                    .setProjectedColumnNames(projection)
                    .cacheBlocks(false)
                    .batchSizeBytes(props.getQuery().getBatchBytes())
                    .readMode(AsyncKuduScanner.ReadMode.READ_LATEST)
                    .scanRequestTimeout(props.getOperationTimeoutMs())
                    .limit(scanLimit);
            for (var predicate : plan.predicates()) {
                builder.addPredicate(predicate);
            }
            scanner = builder.build();

            outer:
            while (scanner.hasMoreRows()) {
                for (RowResult row : scanner.nextRows()) {
                    scanned++;
                    Map<String, Object> values = toMap(row, schema, projection);
                    if (!PredicateBuilder.matches(plan.clientFilters(), schema, values)) {
                        continue;
                    }
                    if (skipped < offset) {
                        skipped++;
                        continue;
                    }
                    if (rows.size() < limit) {
                        rows.add(values);
                    } else {
                        hasNext = true;
                        break outer;
                    }
                    if (scanned >= props.getQuery().getMaxScanRows()) {
                        truncated = true;
                        break outer;
                    }
                }
                if (scanned >= props.getQuery().getMaxScanRows()) {
                    if (scanner.hasMoreRows()) {
                        truncated = true;
                    }
                    break;
                }
            }
        } catch (Exception e) {
            ApiException api = KuduErrors.translate(e, "查询 " + tableName + " 失败");
            throw api == null ? new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "INTERNAL", e.getMessage()) : api;
        } finally {
            if (scanner != null) {
                try {
                    scanner.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响结果
                }
            }
        }

        List<ColumnDef> columns = new ArrayList<>();
        for (String name : projection) {
            columns.add(SchemaMapper.toDef(schema.getColumn(name)));
        }
        return new QueryResponse(columns, rows, limit, offset, rows.size(), hasNext, scanned,
                plan.clientFiltered(), System.currentTimeMillis() - started, truncated);
    }

    public MutationResult mutate(String tableName, MutationRequest request) {
        if (request == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        boolean stopOnError = Boolean.TRUE.equals(request.stopOnError());
        return sessionTemplate.apply(tableName, request.op(), request.rows(), stopOnError);
    }

    /** 解析投影：默认全列；客户端过滤用到的列必须包含在投影里。 */
    private static List<String> resolveProjection(Schema schema, List<String> requested,
                                                  PredicateBuilder.Plan plan) {
        Set<String> columns = new LinkedHashSet<>();
        if (requested == null || requested.isEmpty()) {
            schema.getColumns().forEach(column -> columns.add(column.getName()));
        } else {
            for (String name : requested) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                if (schema.getColumn(name) == null) {
                    throw ApiException.badRequest("列不存在：" + name);
                }
                columns.add(name);
            }
            if (columns.isEmpty()) {
                schema.getColumns().forEach(column -> columns.add(column.getName()));
            }
            for (PredicateBuilder.ClientFilter filter : plan.clientFilters()) {
                columns.add(filter.column());
            }
        }
        return new ArrayList<>(columns);
    }

    private static Map<String, Object> toMap(RowResult row, Schema schema, List<String> projection) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String name : projection) {
            ColumnSchema column = schema.getColumn(name);
            if (column == null) {
                continue;
            }
            values.put(name, KuduValueConverter.read(row, column));
        }
        return values;
    }
}
