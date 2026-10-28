package com.kuducrud.web;

import com.kuducrud.config.ClusterRegistry;
import com.kuducrud.model.TableDetail;
import org.apache.kudu.ColumnSchema;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TableController {

    private final ClusterRegistry registry;

    public TableController(ClusterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/tables")
    public Object tables() {
        return registry.current().catalog().list();
    }

    @GetMapping("/table")
    public TableDetail table(@RequestParam("table") String table) {
        if (table == null || table.isBlank()) {
            throw ApiException.badRequest("table 参数不能为空");
        }
        return registry.current().schema().detail(table);
    }

    /** 前端下拉用的枚举值。 */
    @GetMapping("/meta/options")
    public Map<String, Object> options() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("types", List.of("INT8", "INT16", "INT32", "INT64", "STRING", "VARCHAR", "BOOL",
                "FLOAT", "DOUBLE", "BINARY", "DECIMAL", "DATE", "UNIXTIME_MICROS"));
        result.put("encodings", java.util.Arrays.stream(ColumnSchema.Encoding.values()).map(Enum::name).toList());
        result.put("compressions", java.util.Arrays.stream(ColumnSchema.CompressionAlgorithm.values())
                .map(Enum::name).toList());
        result.put("filterOps", List.of("EQ", "NE", "LT", "LTE", "GT", "GTE", "IN",
                "IS_NULL", "IS_NOT_NULL", "PREFIX"));
        result.put("mutationOps", List.of("INSERT", "UPDATE", "UPSERT", "DELETE"));
        return result;
    }
}
