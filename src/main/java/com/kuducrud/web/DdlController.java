package com.kuducrud.web;

import com.kuducrud.config.ClusterRegistry;
import com.kuducrud.model.ColumnDef;
import com.kuducrud.model.CreateTableRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class DdlController {

    private final ClusterRegistry registry;

    public DdlController(ClusterRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/table/create")
    public Map<String, Object> create(@RequestBody CreateTableRequest request) {
        registry.current().ddl().create(request);
        return Map.of("ok", true, "table", request.name());
    }

    public record ConfirmRequest(String confirm, String newName) {
    }

    @PostMapping("/table/drop")
    public Map<String, Object> drop(@RequestParam("table") String table,
                                    @RequestBody(required = false) ConfirmRequest request) {
        registry.current().ddl().drop(table, request == null ? null : request.confirm());
        return Map.of("ok", true, "table", table);
    }

    @PostMapping("/table/rename")
    public Map<String, Object> rename(@RequestParam("table") String table,
                                      @RequestBody ConfirmRequest request) {
        if (request == null || request.newName() == null || request.newName().isBlank()) {
            throw ApiException.badRequest("newName 不能为空");
        }
        registry.current().ddl().rename(table, request.newName().trim());
        return Map.of("ok", true, "table", request.newName().trim());
    }

    @PostMapping("/table/columns/add")
    public Map<String, Object> addColumn(@RequestParam("table") String table, @RequestBody ColumnDef column) {
        registry.current().ddl().addColumn(table, column);
        return Map.of("ok", true);
    }

    public record DropColumnRequest(String column) {
    }

    @PostMapping("/table/columns/drop")
    public Map<String, Object> dropColumn(@RequestParam("table") String table,
                                          @RequestBody DropColumnRequest request) {
        if (request == null || request.column() == null || request.column().isBlank()) {
            throw ApiException.badRequest("column 不能为空");
        }
        registry.current().ddl().dropColumn(table, request.column());
        return Map.of("ok", true);
    }

    public record ReplicasRequest(Integer replicas) {
    }

    @PostMapping("/table/replicas")
    public Map<String, Object> replicas(@RequestParam("table") String table,
                                        @RequestBody ReplicasRequest request) {
        if (request == null || request.replicas() == null) {
            throw ApiException.badRequest("replicas 不能为空");
        }
        String method = registry.current().ddl().setReplicas(table, request.replicas());
        return Map.of("ok", true, "method", method);
    }
}
