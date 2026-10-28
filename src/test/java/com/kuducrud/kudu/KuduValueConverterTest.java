package com.kuducrud.kudu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.ColumnTypeAttributes;
import org.apache.kudu.Schema;
import org.apache.kudu.Type;
import org.apache.kudu.client.PartialRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全类型写入/谓词取值回归：覆盖 BINARY/DECIMAL/DATE/VARCHAR 这些容易写错的分支。
 */
class KuduValueConverterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ColumnSchema column(String name, Type type) {
        ColumnSchema.ColumnSchemaBuilder builder = new ColumnSchema.ColumnSchemaBuilder(name, type).nullable(true);
        if (type == Type.VARCHAR) {
            builder.typeAttributes(new ColumnTypeAttributes.ColumnTypeAttributesBuilder().length(64).build());
        }
        return builder.build();
    }

    private static ColumnSchema decimalColumn() {
        return new ColumnSchema.ColumnSchemaBuilder("c_dec", Type.DECIMAL)
                .nullable(true)
                .typeAttributes(new ColumnTypeAttributes.ColumnTypeAttributesBuilder()
                        .precision(12).scale(2).build())
                .build();
    }

    @Test
    void writesAllScalarTypes() throws Exception {
        List<ColumnSchema> columns = List.of(
                column("c_i8", Type.INT8),
                column("c_i16", Type.INT16),
                column("c_i32", Type.INT32),
                column("c_i64", Type.INT64),
                column("c_str", Type.STRING),
                column("c_vch", Type.VARCHAR),
                column("c_bool", Type.BOOL),
                column("c_f", Type.FLOAT),
                column("c_d", Type.DOUBLE),
                decimalColumn(),
                column("c_date", Type.DATE),
                column("c_ts", Type.UNIXTIME_MICROS),
                column("c_bin", Type.BINARY));
        Schema schema = new Schema(columns);
        PartialRow row = schema.newPartialRow();

        KuduValueConverter.write(row, schema.getColumn("c_i8"), MAPPER.readTree("-8"));
        KuduValueConverter.write(row, schema.getColumn("c_i16"), MAPPER.readTree("1600"));
        KuduValueConverter.write(row, schema.getColumn("c_i32"), MAPPER.readTree("32"));
        KuduValueConverter.write(row, schema.getColumn("c_i64"), MAPPER.readTree("\"9007199254740993\""));
        KuduValueConverter.write(row, schema.getColumn("c_str"), MAPPER.readTree("\"hello\""));
        KuduValueConverter.write(row, schema.getColumn("c_vch"), MAPPER.readTree("\"varchar\""));
        KuduValueConverter.write(row, schema.getColumn("c_bool"), MAPPER.readTree("true"));
        KuduValueConverter.write(row, schema.getColumn("c_f"), MAPPER.readTree("1.5"));
        KuduValueConverter.write(row, schema.getColumn("c_d"), MAPPER.readTree("2.25"));
        KuduValueConverter.write(row, schema.getColumn("c_dec"), MAPPER.readTree("\"1234.5\""));
        KuduValueConverter.write(row, schema.getColumn("c_date"), MAPPER.readTree("\"2026-10-28\""));
        KuduValueConverter.write(row, schema.getColumn("c_ts"), MAPPER.readTree("\"2026-10-28T10:00:00Z\""));
        KuduValueConverter.write(row, schema.getColumn("c_bin"), MAPPER.readTree("\"aGVsbG8=\""));

        assertEquals((byte) -8, row.getByte("c_i8"));
        assertEquals((short) 1600, row.getShort("c_i16"));
        assertEquals(32, row.getInt("c_i32"));
        assertEquals(9007199254740993L, row.getLong("c_i64"));
        assertEquals("varchar", row.getVarchar("c_vch"));
        assertTrue(row.getBoolean("c_bool"));
        assertEquals(0, row.getDecimal("c_dec").compareTo(new BigDecimal("1234.5")));
        assertEquals("2026-10-28", row.getDate("c_date").toLocalDate().toString());
        assertEquals("hello", new String(row.getBinaryCopy("c_bin")));
    }

    @Test
    void decimalScaleIsEnforced() throws Exception {
        PartialRow row = new Schema(List.of(decimalColumn())).newPartialRow();
        ColumnSchema column = decimalColumn();
        assertThrows(IllegalArgumentException.class,
                () -> KuduValueConverter.write(row, column, MAPPER.readTree("\"1.234\"")));
    }

    @Test
    void predicateValuesUseKuduJavaTypes() throws Exception {
        assertEquals(Byte.valueOf((byte) 8),
                KuduValueConverter.predicateValue(column("c", Type.INT8), MAPPER.readTree("8"), "c"));
        assertEquals(Integer.valueOf(8),
                KuduValueConverter.predicateValue(column("c", Type.INT32), MAPPER.readTree("\"8\""), "c"));
        assertEquals(0, ((BigDecimal) KuduValueConverter.predicateValue(
                decimalColumn(), MAPPER.readTree("1.2"), "c_dec")).compareTo(new BigDecimal("1.2")));
        assertEquals("2026-10-28",
                KuduValueConverter.predicateValue(column("c", Type.DATE), MAPPER.readTree("\"2026-10-28\""), "c")
                        .toString());
        assertTrue(KuduValueConverter.predicateValue(column("c", Type.BINARY), MAPPER.readTree("\"aGk=\""), "c")
                instanceof byte[]);
    }

    @Test
    void defaultValuesAreTypeAware() {
        assertEquals(Integer.valueOf(5), KuduValueConverter.defaultValue(Type.INT32, "5"));
        assertEquals(new BigDecimal("1.50"), KuduValueConverter.defaultValue(Type.DECIMAL, "1.50"));
        assertEquals("2026-01-02", KuduValueConverter.defaultValue(Type.DATE, "2026-01-02").toString());
        assertNull(KuduValueConverter.defaultValue(Type.STRING, "  "));
    }}
