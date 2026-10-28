package com.kuducrud.kudu;

import com.fasterxml.jackson.databind.JsonNode;
import com.kuducrud.config.KuduProperties;
import com.kuducrud.model.MutationResult;
import com.kuducrud.web.ApiException;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Schema;
import org.apache.kudu.client.KuduSession;
import org.apache.kudu.client.KuduTable;
import org.apache.kudu.client.Operation;
import org.apache.kudu.client.OperationResponse;
import org.apache.kudu.client.PartialRow;
import org.apache.kudu.client.SessionConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.kuducrud.kudu.KuduErrors.translate;

/**
 * 行写入模板：MANUAL_FLUSH + 分批 flush + 逐 response 校验行错误。
 * <p>KuduSession 非线程安全，每次调用新建并关闭；MANUAL_FLUSH 无回滚语义，
 * 失败响应里会逐行报告，已提交的行不会回滚。
 */
public class KuduSessionTemplate {

    private static final Logger log = LoggerFactory.getLogger(KuduSessionTemplate.class);
    private static final int MAX_ROWS_PER_REQUEST = 5000;

    private final KuduConnectionManager connection;
    private final KuduProperties props;

    public KuduSessionTemplate(KuduConnectionManager connection, KuduProperties props) {
        this.connection = connection;
        this.props = props;
    }

    public MutationResult apply(String tableName, String opRaw, List<Map<String, JsonNode>> rows, boolean stopOnError) {
        if (rows == null || rows.isEmpty()) {
            throw ApiException.badRequest("rows 不能为空");
        }
        if (rows.size() > MAX_ROWS_PER_REQUEST) {
            throw ApiException.badRequest("单次最多写入 " + MAX_ROWS_PER_REQUEST + " 行，请分批提交");
        }
        String op = opRaw == null ? "UPSERT" : opRaw.trim().toUpperCase();
        if (!List.of("INSERT", "UPSERT", "UPDATE", "DELETE").contains(op)) {
            throw ApiException.badRequest("不支持的操作：" + opRaw);
        }

        KuduTable table = connection.table(tableName);
        Schema schema = table.getSchema();
        int batchSize = Math.max(1, props.getWrite().getBatchSize());

        Acc acc = new Acc();
        List<Integer> pending = new ArrayList<>(batchSize);
        KuduSession session = connection.client().newSession();
        session.setFlushMode(SessionConfiguration.FlushMode.MANUAL_FLUSH);
        session.setMutationBufferSpace(batchSize);
        session.setTimeoutMillis(props.getOperationTimeoutMs());
        try {
            for (int i = 0; i < rows.size(); i++) {
                Map<String, JsonNode> values = rows.get(i);
                if (values == null) {
                    acc.fail(i, "-", "行数据为空");
                    continue;
                }
                try {
                    Operation operation = buildOperation(table, op);
                    fill(operation.getRow(), schema, values, op);
                    session.apply(operation);
                    pending.add(i);
                } catch (IllegalArgumentException e) {
                    acc.fail(i, keyOf(schema, values), e.getMessage());
                    if (stopOnError) {
                        break;
                    }
                } catch (Exception e) {
                    acc.fail(i, keyOf(schema, values), messageOf(e));
                    if (stopOnError) {
                        break;
                    }
                }
                if (pending.size() >= batchSize) {
                    flush(session, pending, acc);
                }
                if (stopOnError && acc.failed > 0) {
                    break;
                }
            }
            flush(session, pending, acc);
        } finally {
            try {
                session.close();
            } catch (Exception e) {
                log.warn("close kudu session failed: {}", e.getMessage());
            }
        }
        return new MutationResult(op, rows.size(), acc.applied, acc.failed, acc.errors);
    }

    private static Operation buildOperation(KuduTable table, String op) {
        return switch (op) {
            case "INSERT" -> table.newInsert();
            case "UPDATE" -> table.newUpdate();
            case "DELETE" -> table.newDelete();
            default -> table.newUpsert();
        };
    }

    private static void fill(PartialRow row, Schema schema, Map<String, JsonNode> values, String op) {
        for (ColumnSchema key : schema.getPrimaryKeyColumns()) {
            if (!values.containsKey(key.getName()) || values.get(key.getName()) == null
                    || values.get(key.getName()).isNull()) {
                throw new IllegalArgumentException("缺少主键列 " + key.getName());
            }
        }
        boolean delete = "DELETE".equals(op);
        for (Map.Entry<String, JsonNode> entry : values.entrySet()) {
            ColumnSchema column = schema.getColumn(entry.getKey());
            if (column == null) {
                throw new IllegalArgumentException("未知列：" + entry.getKey());
            }
            if (delete && !column.isKey()) {
                continue; // 删除只需要主键
            }
            try {
                KuduValueConverter.write(row, column, entry.getValue());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("列 " + column.getName() + "：" + e.getMessage(), e);
            }
        }
    }

    private static String keyOf(Schema schema, Map<String, JsonNode> values) {
        StringBuilder sb = new StringBuilder("(");
        List<ColumnSchema> keys = schema.getPrimaryKeyColumns();
        for (int i = 0; i < keys.size(); i++) {
            String name = keys.get(i).getName();
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(name).append('=').append(values.get(name) == null ? "null" : values.get(name).asText());
        }
        return sb.append(')').toString();
    }

    private void flush(KuduSession session, List<Integer> pending, Acc acc) {
        if (pending.isEmpty()) {
            return;
        }
        List<OperationResponse> responses;
        try {
            responses = session.flush();
        } catch (Exception e) {
            ApiException api = translate(e, "提交写入失败");
            for (Integer rowIndex : pending) {
                acc.fail(rowIndex, "-", api == null ? messageOf(e) : api.getMessage());
            }
            pending.clear();
            return;
        }
        if (responses == null) {
            pending.clear();
            return;
        }
        for (int i = 0; i < pending.size() && i < responses.size(); i++) {
            OperationResponse response = responses.get(i);
            int rowIndex = pending.get(i);
            if (response != null && response.hasRowError()) {
                acc.fail(rowIndex, "-", response.getRowError() == null
                        ? "写入失败" : String.valueOf(response.getRowError()));
            } else {
                acc.applied++;
            }
        }
        pending.clear();
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private static final class Acc {
        private int applied;
        private int failed;
        private final List<MutationResult.RowError> errors = new ArrayList<>();

        void fail(int rowIndex, String rowKey, String message) {
            failed++;
            if (errors.size() < 200) {
                errors.add(new MutationResult.RowError(rowIndex, rowKey, message));
            }
        }
    }
}
