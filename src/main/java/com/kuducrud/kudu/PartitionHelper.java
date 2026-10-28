package com.kuducrud.kudu;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.kuducrud.model.PartitionDef;
import com.kuducrud.web.ApiException;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Schema;
import org.apache.kudu.client.CreateTableOptions;
import org.apache.kudu.client.KuduTable;
import org.apache.kudu.client.PartialRow;
import org.apache.kudu.client.Partition;
import org.apache.kudu.client.PartitionSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分区信息捕获与建表分区应用。
 */
public final class PartitionHelper {

    private PartitionHelper() {
    }

    /** 捕获已有表的分区（hash 桶/seed、range 列、range 边界）。 */
    public static PartitionDef capture(KuduTable table, long timeoutMs) {
        Schema schema = table.getSchema();
        PartitionSchema partitionSchema = table.getPartitionSchema();

        List<String> rangeColumns = new ArrayList<>();
        for (Integer columnIndex : partitionSchema.getRangeSchema().getColumnIds()) {
            if (columnIndex != null && columnIndex >= 0 && columnIndex < schema.getColumnCount()) {
                rangeColumns.add(schema.getColumnByIndex(columnIndex).getName());
            }
        }

        List<PartitionDef.HashPartition> hashes = new ArrayList<>();
        int hashProduct = 1;
        for (PartitionSchema.HashBucketSchema hash : partitionSchema.getHashBucketSchemas()) {
            List<String> columns = new ArrayList<>();
            for (Integer columnIndex : hash.getColumnIds()) {
                if (columnIndex != null && columnIndex >= 0 && columnIndex < schema.getColumnCount()) {
                    columns.add(schema.getColumnByIndex(columnIndex).getName());
                }
            }
            hashes.add(new PartitionDef.HashPartition(columns, hash.getNumBuckets(), hash.getSeed()));
            hashProduct *= Math.max(1, hash.getNumBuckets());
        }

        List<PartitionDef.RangeBound> bounds = new ArrayList<>();
        try {
            for (Partition partition : table.getRangePartitions(timeoutMs)) {
                PartialRow start = partition.getDecodedRangeKeyStart(table);
                PartialRow end = partition.getDecodedRangeKeyEnd(table);
                bounds.add(new PartitionDef.RangeBound(
                        start == null ? null : readBound(start, schema, rangeColumns),
                        end == null ? null : readBound(end, schema, rangeColumns)));
            }
        } catch (Exception e) {
            // getRangePartitions 在部分客户端版本标为 Private：读不到就只展示 hash/range 列，不阻断详情页
            org.slf4j.LoggerFactory.getLogger(PartitionHelper.class)
                    .warn("read range partitions of {} failed: {}", table.getName(), e.toString());
            bounds = null;
        }

        int ranges = bounds == null || bounds.isEmpty() ? 1 : bounds.size();
        return new PartitionDef(hashes, rangeColumns, bounds, hashProduct * ranges);
    }

    private static Map<String, com.fasterxml.jackson.databind.JsonNode> readBound(
            PartialRow row, Schema schema, List<String> columns) {
        Map<String, com.fasterxml.jackson.databind.JsonNode> values = new LinkedHashMap<>();
        for (String name : columns) {
            ColumnSchema column = schema.getColumn(name);
            if (column == null) {
                continue;
            }
            // 无界分区上该列的 bound 没有赋值，直接读会抛 "Column value is not set"
            if (!row.isSet(name)) {
                values.put(name, JsonNodeFactory.instance.nullNode());
                continue;
            }
            Object value = KuduValueConverter.readPartial(row, column);
            values.put(name, toJson(value));
        }
        return values;
    }

    private static com.fasterxml.jackson.databind.JsonNode toJson(Object value) {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        if (value == null) {
            return factory.nullNode();
        }
        if (value instanceof Integer i) {
            return factory.numberNode(i);
        }
        if (value instanceof Long l) {
            return factory.numberNode(l);
        }
        if (value instanceof Short s) {
            return factory.numberNode(s);
        }
        if (value instanceof Byte b) {
            return factory.numberNode(b);
        }
        if (value instanceof Double d) {
            return factory.numberNode(d);
        }
        if (value instanceof Float f) {
            return factory.numberNode(f);
        }
        if (value instanceof Boolean bool) {
            return factory.booleanNode(bool);
        }
        return factory.textNode(String.valueOf(value));
    }

    /** 把建表请求里的分区定义应用到 CreateTableOptions。 */
    public static void apply(CreateTableOptions options, List<PartitionDef.HashPartition> hashes,
                             List<String> rangeColumns, List<PartitionDef.RangeBound> rangeBounds, Schema schema) {
        if (hashes != null) {
            for (PartitionDef.HashPartition hash : hashes) {
                if (hash == null || hash.columns() == null || hash.columns().isEmpty()) {
                    continue;
                }
                if (hash.buckets() < 1) {
                    throw ApiException.badRequest("hash 分区的 buckets 必须 ≥ 1");
                }
                List<String> columns = hash.columns();
                if (hash.seed() != 0) {
                    options.addHashPartitions(columns, hash.buckets(), hash.seed());
                } else {
                    options.addHashPartitions(columns, hash.buckets());
                }
            }
        }
        if (rangeColumns != null && !rangeColumns.isEmpty()) {
            options.setRangePartitionColumns(rangeColumns);
        }
        if (rangeBounds != null) {
            for (PartitionDef.RangeBound bound : rangeBounds) {
                PartialRow lower = buildRow(schema, bound.lower());
                PartialRow upper = buildRow(schema, bound.upper());
                if (lower == null && upper == null) {
                    continue; // 全无界分区由 Kudu 默认创建
                }
                options.addRangePartition(lower, upper,
                        org.apache.kudu.client.RangePartitionBound.INCLUSIVE_BOUND,
                        org.apache.kudu.client.RangePartitionBound.EXCLUSIVE_BOUND);
            }
        }
    }

    private static PartialRow buildRow(Schema schema, Map<String, com.fasterxml.jackson.databind.JsonNode> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        PartialRow row = schema.newPartialRow();
        for (Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> entry : values.entrySet()) {
            ColumnSchema column = schema.getColumn(entry.getKey());
            if (column == null) {
                throw ApiException.badRequest("分区边界列不存在：" + entry.getKey());
            }
            KuduValueConverter.write(row, column, entry.getValue());
        }
        return row;
    }
}
