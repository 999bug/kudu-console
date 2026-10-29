package com.kuducrud.kudu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Schema;
import org.apache.kudu.Type;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按表 schema 生成测试行：各类型填合法值，主键自动避让已有数据。
 * <p>纯逻辑，不访问 Kudu，便于单测。主键策略：
 * 整型取现有最大值 + 1，字符串用运行种子 + 序号，VARCHAR 按长度截断，
 * 复合主键的每一列都生成递增值，保证组合唯一。
 */
public final class TestRowGenerator {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private TestRowGenerator() {
    }

    public static List<Map<String, JsonNode>> rows(Schema schema, int count, long seed,
                                                  List<Map<String, JsonNode>> existingKeys) {
        if (count < 1) {
            throw new IllegalArgumentException("生成行数必须大于 0");
        }
        Map<String, Long> numericBases = numericBases(schema, existingKeys);
        Map<String, BigDecimal> decimalBases = decimalBases(schema, existingKeys);
        Map<String, LocalDate> dateBases = dateBases(schema, existingKeys);
        String tag = Long.toString(seed, 36);
        List<Map<String, JsonNode>> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Map<String, JsonNode> row = new LinkedHashMap<>();
            for (ColumnSchema column : schema.getColumns()) {
                JsonNode value = column.isKey()
                        ? keyValue(column, i, count, seed, numericBases, decimalBases, dateBases, tag)
                        : value(column, i, seed, tag);
                row.put(column.getName(), value);
            }
            result.add(row);
        }
        return result;
    }

    private static JsonNode keyValue(ColumnSchema column, int index, int count, long seed,
                                     Map<String, Long> numericBases, Map<String, BigDecimal> decimalBases,
                                     Map<String, LocalDate> dateBases, String tag) {
        String name = column.getName();
        long base = numericBases.getOrDefault(name, 0L);
        return switch (column.getType()) {
            case INT8 -> NODES.numberNode((int) checkedInt(base, index, count, Byte.MIN_VALUE, Byte.MAX_VALUE, name, "INT8"));
            case INT16 -> NODES.numberNode((int) checkedInt(base, index, count, Short.MIN_VALUE, Short.MAX_VALUE, name, "INT16"));
            case INT32 -> NODES.numberNode((int) checkedInt(base, index, count, Integer.MIN_VALUE, Integer.MAX_VALUE, name, "INT32"));
            case INT64 -> NODES.numberNode(checkedInt(base, index, count, Long.MIN_VALUE, Long.MAX_VALUE, name, "INT64"));
            case UNIXTIME_MICROS -> NODES.numberNode(seed * 1000L + index);
            case DATE -> NODES.textNode(dateBases.getOrDefault(name, LocalDate.now().minusDays(1))
                    .plusDays(index + 1).toString());
            case DECIMAL -> NODES.textNode(decimalKey(column, index, decimalBases.getOrDefault(name, BigDecimal.ZERO)));
            case STRING -> NODES.textNode("bk_" + tag + "_" + index);
            case VARCHAR -> NODES.textNode(varcharKey(column, "bk_" + tag + "_" + index));
            case BINARY -> NODES.textNode(Base64.getEncoder()
                    .encodeToString(ByteBuffer.allocate(8).putLong(seed + index).array()));
            default -> throw new IllegalArgumentException("列 " + name + " 的类型 " + column.getType()
                    + " 不能作为主键，无法生成测试数据");
        };
    }

    private static JsonNode value(ColumnSchema column, int index, long seed, String tag) {
        String text = "bk_" + tag + "_" + index;
        return switch (column.getType()) {
            case INT8 -> NODES.numberNode((int) ((seed + index) % 100));
            case INT16 -> NODES.numberNode((int) ((seed + index) % 30000));
            case INT32 -> NODES.numberNode((int) ((seed + index) % 1_000_000));
            case INT64 -> NODES.numberNode(seed + index);
            case FLOAT, DOUBLE -> NODES.numberNode(((seed + index) % 100_000) / 100.0);
            case BOOL -> NODES.booleanNode((seed + index) % 2 == 0);
            case STRING -> NODES.textNode(text);
            case VARCHAR -> NODES.textNode(fit(text, varcharLength(column)));
            case BINARY -> NODES.textNode(Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)));
            case DECIMAL -> NODES.textNode(decimalValue(column, seed + index));
            case DATE -> NODES.textNode(LocalDate.now().plusDays(index % 28).toString());
            case UNIXTIME_MICROS -> NODES.numberNode(seed * 1000L + index);
            default -> throw new IllegalArgumentException("不支持的列类型：" + column.getType() + "（列 " + column.getName() + "）");
        };
    }

    private static long checkedInt(long base, int index, int count, long min, long max, String name, String type) {
        if (base > max - count || base + 1 < min) {
            throw new IllegalArgumentException("主键列 " + name + " 剩余空间不足（" + type + "），"
                    + "现有最大值 " + base + "，本次需要新增 " + count + " 行");
        }
        return base + 1 + index;
    }

    private static String varcharKey(ColumnSchema column, String raw) {
        int length = varcharLength(column);
        if (length < 4) {
            throw new IllegalArgumentException("VARCHAR 主键列 " + column.getName()
                    + " 长度至少为 4，当前 " + length + "，无法生成唯一测试值");
        }
        return fit(raw, length);
    }

    private static int varcharLength(ColumnSchema column) {
        if (column.getTypeAttributes() == null || !column.getTypeAttributes().hasLength()) {
            return 0;
        }
        return column.getTypeAttributes().getLength();
    }

    private static String fit(String value, int length) {
        if (length <= 0 || value.length() <= length) {
            return value;
        }
        return value.substring(value.length() - length);
    }

    private static String decimalKey(ColumnSchema column, int index, BigDecimal base) {
        int scale = decimalScale(column);
        int precision = decimalPrecision(column);
        BigDecimal next = base.setScale(scale, RoundingMode.DOWN)
                .add(BigDecimal.valueOf((long) index + 1, scale));
        int integerDigits = precision - scale;
        if (integerDigits > 0 && next.abs().compareTo(BigDecimal.ONE.movePointRight(integerDigits)) >= 0) {
            throw new IllegalArgumentException("DECIMAL 主键列 " + column.getName() + " 超出 precision 上限");
        }
        return next.toPlainString();
    }

    private static String decimalValue(ColumnSchema column, long raw) {
        int scale = decimalScale(column);
        int precision = decimalPrecision(column);
        int integerDigits = Math.max(1, precision - scale);
        long max = (long) Math.pow(10, Math.min(18, integerDigits));
        long value = Math.abs(raw) % Math.max(1, max);
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.DOWN).toPlainString();
    }

    private static int decimalScale(ColumnSchema column) {
        return column.getTypeAttributes() != null && column.getTypeAttributes().hasScale()
                ? column.getTypeAttributes().getScale() : 0;
    }

    private static int decimalPrecision(ColumnSchema column) {
        return column.getTypeAttributes() != null && column.getTypeAttributes().hasPrecision()
                ? column.getTypeAttributes().getPrecision() : 38;
    }

    private static Map<String, Long> numericBases(Schema schema, List<Map<String, JsonNode>> existingKeys) {
        Map<String, Long> bases = new HashMap<>();
        for (ColumnSchema column : schema.getPrimaryKeyColumns()) {
            if (!isIntegral(column.getType())) {
                continue;
            }
            long max = 0;
            boolean found = false;
            for (Map<String, JsonNode> row : safeRows(existingKeys)) {
                JsonNode node = row.get(column.getName());
                if (node == null || node.isNull()) {
                    continue;
                }
                long value;
                try {
                    value = node.isNumber() ? node.asLong() : Long.parseLong(node.asText().trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (!found || value > max) {
                    max = value;
                    found = true;
                }
            }
            bases.put(column.getName(), found ? max : 0L);
        }
        return bases;
    }

    private static Map<String, BigDecimal> decimalBases(Schema schema, List<Map<String, JsonNode>> existingKeys) {
        Map<String, BigDecimal> bases = new HashMap<>();
        for (ColumnSchema column : schema.getPrimaryKeyColumns()) {
            if (column.getType() != Type.DECIMAL) {
                continue;
            }
            BigDecimal max = BigDecimal.ZERO;
            boolean found = false;
            for (Map<String, JsonNode> row : safeRows(existingKeys)) {
                JsonNode node = row.get(column.getName());
                if (node == null || node.isNull()) {
                    continue;
                }
                try {
                    BigDecimal value = new BigDecimal(node.asText().trim());
                    if (!found || value.compareTo(max) > 0) {
                        max = value;
                        found = true;
                    }
                } catch (NumberFormatException ignored) {
                    // 忽略无法解析的存量值
                }
            }
            bases.put(column.getName(), found ? max : BigDecimal.ZERO);
        }
        return bases;
    }

    private static Map<String, LocalDate> dateBases(Schema schema, List<Map<String, JsonNode>> existingKeys) {
        Map<String, LocalDate> bases = new HashMap<>();
        for (ColumnSchema column : schema.getPrimaryKeyColumns()) {
            if (column.getType() != Type.DATE) {
                continue;
            }
            LocalDate max = null;
            for (Map<String, JsonNode> row : safeRows(existingKeys)) {
                JsonNode node = row.get(column.getName());
                if (node == null || node.isNull()) {
                    continue;
                }
                try {
                    String text = node.asText().trim();
                    LocalDate value = LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
                    if (max == null || value.isAfter(max)) {
                        max = value;
                    }
                } catch (RuntimeException ignored) {
                    // 忽略无法解析的存量值
                }
            }
            bases.put(column.getName(), max == null ? LocalDate.now().minusDays(1) : max);
        }
        return bases;
    }

    private static List<Map<String, JsonNode>> safeRows(List<Map<String, JsonNode>> rows) {
        return rows == null ? List.of() : rows;
    }

    private static boolean isIntegral(Type type) {
        return type == Type.INT8 || type == Type.INT16 || type == Type.INT32 || type == Type.INT64;
    }
}
