package com.kuducrud.kudu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.ColumnTypeAttributes;
import org.apache.kudu.Schema;
import org.apache.kudu.Type;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRowGeneratorTest {

    private static final long SEED = 1_700_000_000_000L;

    @Test
    void generatesTypedValuesAndContinuesIntegerKeys() {
        Schema schema = new Schema(List.of(
                column("id", Type.INT32, true),
                column("name", Type.STRING, false),
                column("cnt", Type.INT64, false),
                column("flag", Type.BOOL, false),
                column("ratio", Type.DOUBLE, false),
                decimal("amount", 10, 2),
                column("day", Type.DATE, false),
                column("ts", Type.UNIXTIME_MICROS, false),
                column("bin", Type.BINARY, false),
                varchar("code", 8)
        ));

        List<Map<String, JsonNode>> rows = TestRowGenerator.rows(schema, 3, SEED,
                List.of(Map.of("id", JsonNodeFactory.instance.numberNode(100))));

        assertEquals(3, rows.size());
        assertEquals(101, rows.get(0).get("id").asInt());
        assertEquals(103, rows.get(2).get("id").asInt());
        assertTrue(rows.get(0).get("name").asText().startsWith("bk_"));
        assertEquals(SEED * 1000L, rows.get(0).get("ts").asLong());
        assertEquals(LocalDate.now().toString(), rows.get(0).get("day").asText());
        assertTrue(rows.get(0).get("amount").asText().matches("\\d+\\.\\d{2}"));
        assertTrue(rows.get(0).get("code").asText().length() <= 8);
        assertEquals("bk_" + Long.toString(SEED, 36) + "_0",
                new String(Base64.getDecoder().decode(rows.get(0).get("bin").asText()), StandardCharsets.UTF_8));
        assertFalse(rows.get(0).get("flag").isNull());
        assertTrue(rows.get(0).get("cnt").asLong() > 0);
    }

    @Test
    void generatesUniqueStringKeysWithinVarcharLength() {
        Schema schema = new Schema(List.of(
                varcharKey("code", 8),
                column("name", Type.STRING, false)
        ));

        List<Map<String, JsonNode>> rows = TestRowGenerator.rows(schema, 2, SEED, List.of());

        String first = rows.get(0).get("code").asText();
        String second = rows.get(1).get("code").asText();
        assertEquals(8, first.length());
        assertEquals(8, second.length());
        assertNotEquals(first, second);
    }

    @Test
    void incrementsEveryPartOfCompositeKey() {
        Schema schema = new Schema(List.of(
                column("id", Type.INT32, true),
                column("code", Type.STRING, true),
                column("name", Type.STRING, false)
        ));
        Map<String, JsonNode> existing = new LinkedHashMap<>();
        existing.put("id", JsonNodeFactory.instance.numberNode(5));
        existing.put("code", JsonNodeFactory.instance.textNode("legacy"));

        List<Map<String, JsonNode>> rows = TestRowGenerator.rows(schema, 2, SEED, List.of(existing));

        assertEquals(6, rows.get(0).get("id").asInt());
        assertEquals(7, rows.get(1).get("id").asInt());
        assertNotEquals(rows.get(0).get("code").asText(), rows.get(1).get("code").asText());
    }

    @Test
    void continuesDatePrimaryKey() {
        Schema schema = new Schema(List.of(column("day", Type.DATE, true)));

        List<Map<String, JsonNode>> rows = TestRowGenerator.rows(schema, 2, SEED,
                List.of(Map.of("day", JsonNodeFactory.instance.textNode("2026-10-28"))));

        assertEquals("2026-10-29", rows.get(0).get("day").asText());
        assertEquals("2026-10-30", rows.get(1).get("day").asText());
    }

    @Test
    void rejectsExhaustedSmallIntegerKey() {
        Schema schema = new Schema(List.of(column("id", Type.INT8, true)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TestRowGenerator.rows(schema, 1, SEED,
                        List.of(Map.of("id", JsonNodeFactory.instance.numberNode(127)))));
        assertTrue(error.getMessage().contains("INT8"));
    }

    private static ColumnSchema column(String name, Type type, boolean key) {
        return new ColumnSchema.ColumnSchemaBuilder(name, type)
                .key(key)
                .nullable(!key)
                .build();
    }

    private static ColumnSchema varchar(String name, int length) {
        return new ColumnSchema.ColumnSchemaBuilder(name, Type.VARCHAR)
                .key(false)
                .nullable(true)
                .typeAttributes(new ColumnTypeAttributes.ColumnTypeAttributesBuilder().length(length).build())
                .build();
    }

    private static ColumnSchema varcharKey(String name, int length) {
        return new ColumnSchema.ColumnSchemaBuilder(name, Type.VARCHAR)
                .key(true)
                .nullable(false)
                .typeAttributes(new ColumnTypeAttributes.ColumnTypeAttributesBuilder().length(length).build())
                .build();
    }

    private static ColumnSchema decimal(String name, int precision, int scale) {
        return new ColumnSchema.ColumnSchemaBuilder(name, Type.DECIMAL)
                .key(false)
                .nullable(true)
                .typeAttributes(new ColumnTypeAttributes.ColumnTypeAttributesBuilder()
                        .precision(precision).scale(scale).build())
                .build();
    }
}
