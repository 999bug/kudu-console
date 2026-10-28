package com.kuducrud.service;

import com.kuducrud.model.ColumnDef;
import com.kuducrud.model.CreateTableRequest;
import com.kuducrud.model.PartitionDef;
import com.kuducrud.web.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DdlValidatorTest {

    private static ColumnDef key(String name) {
        return new ColumnDef(name, "INT32", true, false, null, null, null, null, null, null, null, null);
    }

    private static ColumnDef plain(String name) {
        return new ColumnDef(name, "STRING", false, true, null, null, null, null, null, null, null, null);
    }

    private static CreateTableRequest request(List<ColumnDef> columns,
                                              List<PartitionDef.HashPartition> hashes,
                                              List<String> rangeColumns) {
        return new CreateTableRequest("demo.t1", 1, null, null, columns, hashes, rangeColumns, List.of(), null);
    }

    @Test
    void acceptsSimpleTable() {
        assertDoesNotThrow(() -> DdlValidator.validateCreate(
                request(List.of(key("id"), plain("name")), List.of(), List.of())));
    }

    @Test
    void rejectsMissingPrimaryKey() {
        ApiException e = assertThrows(ApiException.class, () -> DdlValidator.validateCreate(
                request(List.of(plain("name")), List.of(), List.of())));
        assertEquals("VALIDATION_FAILED", e.getCode());
    }

    @Test
    void rejectsNonContiguousPrimaryKey() {
        ApiException e = assertThrows(ApiException.class, () -> DdlValidator.validateCreate(
                request(List.of(key("a"), plain("b"), key("c")), List.of(), List.of())));
        assertEquals("VALIDATION_FAILED", e.getCode());
    }

    @Test
    void rejectsHashColumnOutsidePrimaryKey() {
        ApiException e = assertThrows(ApiException.class, () -> DdlValidator.validateCreate(
                request(List.of(key("id"), plain("name")),
                        List.of(new PartitionDef.HashPartition(List.of("name"), 4, 0)), List.of())));
        assertEquals("VALIDATION_FAILED", e.getCode());
    }

    @Test
    void rangeColumnsMustBePrimaryKeyPrefix() {
        ApiException e = assertThrows(ApiException.class, () -> DdlValidator.validateCreate(
                request(List.of(key("a"), key("b"), plain("c")), List.of(), List.of("b"))));
        assertEquals("VALIDATION_FAILED", e.getCode());
        assertDoesNotThrow(() -> DdlValidator.validateCreate(
                request(List.of(key("a"), key("b"), plain("c")), List.of(), List.of("a", "b"))));
    }

    @Test
    void rejectsSystemTableName() {
        assertThrows(ApiException.class, () -> DdlValidator.validateTableName("sys.catalog"));
    }

    @Test
    void rejectsEvenReplicaCount() {
        CreateTableRequest req = new CreateTableRequest("demo.t1", 4, null, null,
                List.of(key("id")), List.of(), List.of(), List.of(), null);
        assertThrows(ApiException.class, () -> DdlValidator.validateCreate(req));
    }
}
