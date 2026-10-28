package com.kuducrud.web;

import com.kuducrud.config.ClusterRegistry;
import com.kuducrud.model.MutationRequest;
import com.kuducrud.model.MutationResult;
import com.kuducrud.model.QueryRequest;
import com.kuducrud.model.QueryResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RowController {

    private final ClusterRegistry registry;

    public RowController(ClusterRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/table/query")
    public QueryResponse query(@RequestParam("table") String table,
                               @RequestBody(required = false) QueryRequest request) {
        if (table == null || table.isBlank()) {
            throw ApiException.badRequest("table 参数不能为空");
        }
        return registry.current().rows().query(table, request);
    }

    @PostMapping("/table/rows")
    public MutationResult mutate(@RequestParam("table") String table,
                                 @RequestBody MutationRequest request) {
        if (table == null || table.isBlank()) {
            throw ApiException.badRequest("table 参数不能为空");
        }
        return registry.current().rows().mutate(table, request);
    }
}
