package com.kuducrud.kudu;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Type;
import org.apache.kudu.client.PartialRow;
import org.apache.kudu.client.RowResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * Kudu 全类型读写的唯一入口。
 * <p>JSON 表示约定：BINARY = base64 字符串，DECIMAL = 十进制字符串，DATE = yyyy-MM-dd，
 * UNIXTIME_MICROS = 微秒数字（字符串也可），INT64 超过 2^53 时输出字符串避免 JS 丢精度。
 */
public final class KuduValueConverter {

    /** JS Number.MAX_SAFE_INTEGER */
    private static final long JS_MAX_SAFE = 9_007_199_254_740_991L;

    private KuduValueConverter() {
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    public static Object read(RowResult row, ColumnSchema column) {
        String name = column.getName();
        if (row.isNull(name)) {
            return null;
        }
        return switch (column.getType()) {
            case INT8 -> row.getByte(name);
            case INT16 -> row.getShort(name);
            case INT32 -> row.getInt(name);
            case INT64, UNIXTIME_MICROS -> safeLong(row.getLong(name));
            case FLOAT -> row.getFloat(name);
            case DOUBLE -> row.getDouble(name);
            case BOOL -> row.getBoolean(name);
            case STRING -> row.getString(name);
            case VARCHAR -> row.getVarchar(name);
            case BINARY -> Base64.getEncoder().encodeToString(row.getBinaryCopy(name));
            case DECIMAL -> plain(row.getDecimal(name));
            case DATE -> dateText(row.getDate(name));
            default -> row.getObject(name);
        };
    }

    /** 从 PartialRow（分区边界）读取，类型分支与 {@link #read} 保持一致。 */
    public static Object readPartial(PartialRow row, ColumnSchema column) {
        String name = column.getName();
        if (row.isNull(name)) {
            return null;
        }
        return switch (column.getType()) {
            case INT8 -> row.getByte(name);
            case INT16 -> row.getShort(name);
            case INT32 -> row.getInt(name);
            case INT64, UNIXTIME_MICROS -> safeLong(row.getLong(name));
            case FLOAT -> row.getFloat(name);
            case DOUBLE -> row.getDouble(name);
            case BOOL -> row.getBoolean(name);
            case STRING -> row.getString(name);
            case VARCHAR -> row.getVarchar(name);
            case BINARY -> Base64.getEncoder().encodeToString(row.getBinaryCopy(name));
            case DECIMAL -> plain(row.getDecimal(name));
            case DATE -> dateText(row.getDate(name));
            default -> row.getObject(name);
        };
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /**
     * 把 JSON 值写入 PartialRow。null 走 setNull；一律用 set* 覆盖语义（add* 二次赋值会抛）。
     */
    public static void write(PartialRow row, ColumnSchema column, JsonNode node) {
        String name = column.getName();
        if (node == null || node.isNull()) {
            row.setNull(name);
            return;
        }
        switch (column.getType()) {
            case INT8 -> row.addByte(name, (byte) asLong(node, name));
            case INT16 -> row.addShort(name, (short) asLong(node, name));
            case INT32 -> row.addInt(name, (int) asLong(node, name));
            case INT64 -> row.addLong(name, asLong(node, name));
            case UNIXTIME_MICROS -> row.addLong(name, asMicros(node, name));
            case FLOAT -> row.addFloat(name, (float) asDouble(node, name));
            case DOUBLE -> row.addDouble(name, asDouble(node, name));
            case BOOL -> row.addBoolean(name, asBoolean(node, name));
            case STRING -> row.addString(name, asText(node));
            case VARCHAR -> row.addVarchar(name, asText(node));
            case BINARY -> row.addBinary(name, decodeBase64(asText(node), name));
            case DECIMAL -> row.addDecimal(name, decimalOf(node, column));
            case DATE -> row.addDate(name, sqlDate(node, name));
            default -> throw new IllegalArgumentException("不支持的列类型：" + column.getType() + "（列 " + name + "）");
        }
    }

    // ------------------------------------------------------------------
    // 谓词值 / 默认值
    // ------------------------------------------------------------------

    /** 生成 KuduPredicate 需要的 Java 类型值。 */
    public static Object predicateValue(ColumnSchema column, JsonNode node, String columnName) {
        return switch (column.getType()) {
            case INT8 -> Byte.valueOf((byte) asLong(node, columnName));
            case INT16 -> Short.valueOf((short) asLong(node, columnName));
            case INT32 -> Integer.valueOf((int) asLong(node, columnName));
            case INT64 -> Long.valueOf(asLong(node, columnName));
            case UNIXTIME_MICROS -> Long.valueOf(asMicros(node, columnName));
            case FLOAT -> Float.valueOf((float) asDouble(node, columnName));
            case DOUBLE -> Double.valueOf(asDouble(node, columnName));
            case BOOL -> Boolean.valueOf(asBoolean(node, columnName));
            case STRING, VARCHAR -> asText(node);
            case BINARY -> decodeBase64(asText(node), columnName);
            case DECIMAL -> decimalOf(node, column);
            case DATE -> sqlDate(node, columnName);
            default -> throw new IllegalArgumentException("不支持的谓词类型：" + column.getType());
        };
    }

    /** 解析列默认值（建表/加列时使用），返回 ColumnSchemaBuilder 需要的运行时类型。 */
    public static Object defaultValue(Type type, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        return switch (type) {
            case INT8 -> Byte.parseByte(text);
            case INT16 -> Short.parseShort(text);
            case INT32 -> Integer.parseInt(text);
            case INT64 -> Long.parseLong(text);
            case UNIXTIME_MICROS -> microsOf(text);
            case FLOAT -> Float.parseFloat(text);
            case DOUBLE -> Double.parseDouble(text);
            case BOOL -> Boolean.parseBoolean(text);
            case STRING, VARCHAR -> text;
            case BINARY -> Base64.getDecoder().decode(text);
            case DECIMAL -> new BigDecimal(text);
            case DATE -> java.sql.Date.valueOf(LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text));
            default -> text;
        };
    }

    /** 把 ColumnSchema 上的默认值格式化成前端可编辑的字符串。 */
    public static String formatDefault(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Byte b) {
            return Byte.toString(b);
        }
        return String.valueOf(value);
    }

    // ------------------------------------------------------------------
    // 值拷贝（扫描 → 删除/写入，避免 JSON 往返）
    // ------------------------------------------------------------------

    public static void copyValue(RowResult source, PartialRow target, ColumnSchema column) {
        String name = column.getName();
        if (source.isNull(name)) {
            target.setNull(name);
            return;
        }
        switch (column.getType()) {
            case INT8 -> target.addByte(name, source.getByte(name));
            case INT16 -> target.addShort(name, source.getShort(name));
            case INT32 -> target.addInt(name, source.getInt(name));
            case INT64, UNIXTIME_MICROS -> target.addLong(name, source.getLong(name));
            case FLOAT -> target.addFloat(name, source.getFloat(name));
            case DOUBLE -> target.addDouble(name, source.getDouble(name));
            case BOOL -> target.addBoolean(name, source.getBoolean(name));
            case STRING -> target.addString(name, source.getString(name));
            case VARCHAR -> target.addVarchar(name, source.getVarchar(name));
            case BINARY -> target.addBinary(name, source.getBinaryCopy(name));
            case DECIMAL -> target.addDecimal(name, source.getDecimal(name));
            case DATE -> target.addDate(name, source.getDate(name));
            default -> throw new IllegalArgumentException("不支持的类型：" + column.getType());
        }
    }

    // ------------------------------------------------------------------
    // 基础解析
    // ------------------------------------------------------------------

    private static long asLong(JsonNode node, String column) {
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        String text = node.asText().trim();
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            try {
                return new BigDecimal(text).longValueExact();
            } catch (ArithmeticException | NumberFormatException ex) {
                throw new IllegalArgumentException("列 " + column + " 需要整数，实际值：" + text);
            }
        }
    }

    private static double asDouble(JsonNode node, String column) {
        if (node.isNumber()) {
            return node.asDouble();
        }
        String text = node.asText().trim();
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("列 " + column + " 需要数字，实际值：" + text);
        }
    }

    private static boolean asBoolean(JsonNode node, String column) {
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        String text = node.asText().trim();
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
            return false;
        }
        throw new IllegalArgumentException("列 " + column + " 需要布尔值，实际值：" + text);
    }

    private static String asText(JsonNode node) {
        return node.asText();
    }

    private static byte[] decodeBase64(String text, String column) {
        try {
            return Base64.getDecoder().decode(text.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("列 " + column + " 需要 base64 编码的二进制值");
        }
    }

    private static BigDecimal decimalOf(JsonNode node, ColumnSchema column) {
        BigDecimal value = new BigDecimal(node.asText().trim());
        if (column.getTypeAttributes() != null && column.getTypeAttributes().hasScale()) {
            int scale = column.getTypeAttributes().getScale();
            if (value.scale() > scale) {
                throw new IllegalArgumentException("列 " + column.getName() + " 小数位最多 " + scale + " 位，实际 " + value.scale());
            }
            value = value.setScale(scale, java.math.RoundingMode.UNNECESSARY);
        }
        return value;
    }

    /** 微秒：接受数字（微秒）或 ISO-8601 字符串。 */
    private static long asMicros(JsonNode node, String column) {
        if (node.isNumber()) {
            return node.asLong();
        }
        return microsOf(node.asText().trim());
    }

    private static long microsOf(String text) {
        if (text.chars().allMatch(Character::isDigit)) {
            return Long.parseLong(text);
        }
        try {
            return Math.multiplyExact(Instant.parse(text).toEpochMilli(), 1000L);
        } catch (DateTimeParseException | ArithmeticException ignored) {
            // 继续尝试带偏移的本地时间
        }
        try {
            return Math.multiplyExact(OffsetDateTime.parse(text).toInstant().toEpochMilli(), 1000L);
        } catch (DateTimeParseException | ArithmeticException e) {
            throw new IllegalArgumentException("时间需要微秒数字或 ISO-8601 文本，实际值：" + text);
        }
    }

    private static java.sql.Date sqlDate(JsonNode node, String column) {
        String text = node.asText().trim();
        try {
            LocalDate date = LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
            return java.sql.Date.valueOf(date);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("列 " + column + " 需要 yyyy-MM-dd 日期，实际值：" + text);
        }
    }

    private static Object safeLong(long value) {
        return Math.abs(value) > JS_MAX_SAFE ? String.valueOf(value) : value;
    }

    private static String dateText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (value instanceof LocalDate d) {
            return d.toString();
        }
        return String.valueOf(value);
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /** 判断类型能否作为主键（Kudu 不允许 FLOAT/DOUBLE/BOOL 作主键）。 */
    public static boolean canBePrimaryKey(Type type) {
        return type != Type.FLOAT && type != Type.DOUBLE && type != Type.BOOL;
    }
}
