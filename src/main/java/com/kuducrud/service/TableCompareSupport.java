package com.kuducrud.service;

import com.kuducrud.kudu.KuduValueConverter;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Schema;
import org.apache.kudu.Type;
import org.apache.kudu.client.RowResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 表比对的规范化工具：schema 签名与整行 / 主键编码。
 * <p>口径参考 KuduTableCompareIT：
 * <ul>
 *   <li>schema 按列名排序后比对，列顺序、encoding、compression 不影响结果；</li>
 *   <li>DECIMAL 比 precision/scale，VARCHAR 比 length，主键与可空标记参与签名；</li>
 *   <li>整行按列名排序编码，值按类型规范化（NULL / 字符串转义 / BINARY Base64 / DECIMAL plain），
 *       避免列顺序或格式差异造成误报。</li>
 * </ul>
 */
final class TableCompareSupport {

    static final int SAMPLE_MAX = 500;

    private TableCompareSupport() {
    }

    static String columnSignature(ColumnSchema column) {
        StringBuilder signature = new StringBuilder(column.getName()).append(':').append(column.getType().name());
        if (column.getType() == Type.DECIMAL && column.getTypeAttributes() != null) {
            signature.append('(').append(column.getTypeAttributes().getPrecision())
                    .append(',').append(column.getTypeAttributes().getScale()).append(')');
        }
        if (column.getType() == Type.VARCHAR && column.getTypeAttributes() != null
                && column.getTypeAttributes().hasLength()) {
            signature.append('(').append(column.getTypeAttributes().getLength()).append(')');
        }
        if (column.isKey()) {
            signature.append(":key");
        }
        if (column.isNullable()) {
            signature.append(":nullable");
        }
        return signature.toString();
    }

    /** 按列名排序的 schema 签名，列顺序不同不产生差异。 */
    static List<String> schemaSignature(Schema schema) {
        return schema.getColumns().stream()
                .map(TableCompareSupport::columnSignature)
                .sorted()
                .collect(Collectors.toList());
    }

    static Map<String, String> signatureMap(Schema schema) {
        Map<String, String> result = new LinkedHashMap<>();
        for (ColumnSchema column : schema.getColumns()) {
            result.put(column.getName(), columnSignature(column));
        }
        return result;
    }

    /** 主键列顺序（Kudu 语义的一部分，单独比对）。 */
    static List<String> primaryKeyNames(Schema schema) {
        List<String> names = new ArrayList<>();
        for (ColumnSchema column : schema.getPrimaryKeyColumns()) {
            names.add(column.getName());
        }
        return names;
    }

    /** 主键按列名排序后编码，消除主键顺序差异对数据对账的影响。 */
    static String canonicalKey(RowResult row, Schema schema) {
        return schema.getPrimaryKeyColumns().stream()
                .sorted(Comparator.comparing(ColumnSchema::getName))
                .map(column -> cell(row, column))
                .collect(Collectors.joining("|"));
    }

    /** 整行按列名排序后编码，列顺序不同不产生差异。 */
    static String canonicalRow(RowResult row, Schema schema) {
        return schema.getColumns().stream()
                .sorted(Comparator.comparing(ColumnSchema::getName))
                .map(column -> cell(row, column))
                .collect(Collectors.joining("|"));
    }

    static String abbrev(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= SAMPLE_MAX ? value : value.substring(0, SAMPLE_MAX) + "...";
    }

    private static String cell(RowResult row, ColumnSchema column) {
        Object value = KuduValueConverter.read(row, column);
        if (value == null) {
            return column.getName() + "=NULL";
        }
        String text = display(value);
        if (quoted(column.getType())) {
            text = '"' + escape(text) + '"';
        }
        return column.getName() + '=' + text;
    }

    private static boolean quoted(Type type) {
        return type == Type.STRING || type == Type.VARCHAR || type == Type.BINARY
                || type == Type.DATE || type == Type.DECIMAL;
    }

    private static String display(Object value) {
        if (value instanceof Float number) {
            return Float.toString(number);
        }
        if (value instanceof Double number) {
            return Double.toString(number);
        }
        return String.valueOf(value);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("|", "\\|")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
