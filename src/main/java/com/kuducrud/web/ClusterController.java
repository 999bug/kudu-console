package com.kuducrud.web;

import com.kuducrud.config.ClusterRegistry;
import com.kuducrud.config.ClusterRegistry.ClusterRequest;
import com.kuducrud.config.KuduProperties;
import com.kuducrud.model.ClusterConfig;
import com.kuducrud.model.ClusterStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ClusterController {

    private final ClusterRegistry registry;
    private final KuduProperties props;

    public ClusterController(ClusterRegistry registry, KuduProperties props) {
        this.registry = registry;
        this.props = props;
    }

    /** 当前选中平台的状态（优先取 X-Kudu-Cluster 头 / cluster 参数，缺省为激活平台）。 */
    @GetMapping("/cluster/status")
    public ClusterStatus status() {
        return registry.current().statusSnapshot();
    }

    @GetMapping("/clusters")
    public Map<String, Object> clusters() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeId", registry.activeId());
        List<Map<String, Object>> items = registry.summaries().stream().map(summary -> {
            ClusterConfig config = summary.config();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", config.id());
            item.put("name", config.name());
            item.put("masters", config.masters());
            item.put("operationTimeoutMs", config.operationTimeoutMs());
            item.put("adminTimeoutMs", config.adminTimeoutMs());
            item.put("webUiEnabled", config.webUiEnabled());
            item.put("webUiPortOffset", config.webUiPortOffset());
            item.put("active", summary.active());
            return item;
        }).toList();
        result.put("clusters", items);
        return result;
    }

    @PostMapping("/clusters")
    public ClusterConfig create(@RequestBody ClusterRequest request) {
        return registry.create(request);
    }

    @PutMapping("/clusters/{id}")
    public ClusterConfig update(@PathVariable("id") String id, @RequestBody ClusterRequest request) {
        return registry.update(id, request);
    }

    @DeleteMapping("/clusters/{id}")
    public Map<String, Object> delete(@PathVariable("id") String id) {
        registry.delete(id);
        return Map.of("ok", true, "activeId", registry.activeId());
    }

    @PostMapping("/clusters/{id}/activate")
    public Map<String, Object> activate(@PathVariable("id") String id) {
        registry.activate(id);
        return Map.of("ok", true, "activeId", id);
    }

    public record TestRequest(String masters) {
    }

    @PostMapping("/clusters/test")
    public Object test(@RequestBody TestRequest request) {
        if (request == null || request.masters() == null || request.masters().isBlank()) {
            throw ApiException.badRequest("masters 不能为空");
        }
        return registry.probe(request.masters());
    }

    /** 全局查询限制，供前端展示。 */
    @GetMapping("/meta/limits")
    public Map<String, Object> limits() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("defaultLimit", props.getQuery().getDefaultLimit());
        result.put("maxLimit", props.getQuery().getMaxLimit());
        result.put("maxOffset", props.getQuery().getMaxOffset());
        result.put("maxScanRows", props.getQuery().getMaxScanRows());
        result.put("batchSize", props.getWrite().getBatchSize());
        result.put("truncateMaxRows", props.getTruncate().getMaxRows());
        return result;
    }
}
