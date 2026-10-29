package com.kuducrud.service;

import org.apache.kudu.ColumnSchema;
import org.apache.kudu.ColumnTypeAttributes;
import org.apache.kudu.Schema;
import org.apache.kudu.Type;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableCompareSupportTest {

    @Test
    void signatureIgnoresColumnOrderButKeepsTypeAttributes() {
        Schema left = new Schema(List.of(
                column("id", Type.INT32, true, false),
                column("name", Type.STRING, false, true),
                decimal("amount", 10, 2),
                varchar("code", 8)
        ));
        Schema right = new Schema(List.of(
                varchar("code", 8),
                decimal("amount", 10, 2),
                column("name", Type.STRING, false, true),
                column("id", Type.INT32, true, false)
        ));

        assertEquals(TableCompareSupport.schemaSignature(left), TableCompareSupport.schemaSignature(right));
        List<String> signature = TableCompareSupport.schemaSignature(left);
        assertTrue(signature.contains("id:INT32:key"));
        assertTrue(signature.contains("name:STRING:nullable"));
        assertTrue(signature.contains("amount:DECIMAL(10,2):nullable"));
        assertTrue(signature.contains("code:VARCHAR(8):nullable"));
    }

    @Test
    void detectsDecimalAndVarcharAttributeDifferences() {
        Schema left = new Schema(List.of(decimal("amount", 10, 2), varchar("code", 8)));
        Schema right = new Schema(List.of(decimal("amount", 12, 2), varchar("code", 16)));

        assertFalse(TableCompareSupport.signatureMap(left).equals(TableCompareSupport.signatureMap(right)));
    }

    @Test
    void keepsPrimaryKeyOrder() {
        Schema schema = new Schema(List.of(
                column("id", Type.INT32, true, false),
                column("part", Type.STRING, true, false),
                column("value", Type.STRING, false, true)
        ));

        assertEquals(List.of("id", "part"), TableCompareSupport.primaryKeyNames(schema));
    }

    @Test
    void abbreviatesLongSamples() {
        String text = "x".repeat(TableCompareSupport.SAMPLE_MAX + 5);
        String abbreviated = TableCompareSupport.abbrev(text);
        assertEquals(TableCompareSupport.SAMPLE_MAX + 3, abbreviated.length());
        assertTrue(abbreviated.endsWith("..."));
    }

    private static ColumnSchema column(String name, Type type, boolean key, boolean nullable) {
        return new ColumnSchema.ColumnSchemaBuilder(name, type)
                .key(key)
                .nullable(key ? false : nullable)
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

    private static ColumnSchema varchar(String name, int length) {
        return new ColumnSchema.ColumnSchemaBuilder(name, Type.VARCHAR)
                .key(false)
                .nullable(true)
                .typeAttributes(new ColumnTypeAttributes.ColumnTypeAttributesBuilder()
                        .length(length).build())
                .build();
    }
}
