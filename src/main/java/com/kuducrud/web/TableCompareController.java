package com.kuducrud.web;

import com.kuducrud.config.ClusterRegistry;
import com.kuducrud.model.CompareTaskView;
import com.kuducrud.model.TableCompareRequest;
import com.kuducrud.service.TableCompareService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/compare")
public class TableCompareController {

    private final ClusterRegistry registry;
    private final TableCompareService compare;

    public TableCompareController(ClusterRegistry registry, TableCompareService compare) {
        this.registry = registry;
        this.compare = compare;
    }

    /** 启动异步比对，返回任务视图；轮询 GET /api/compare/{id} 获取进度与结果。 */
    @PostMapping
    public CompareTaskView start(@RequestBody TableCompareRequest request) {
        return compare.submit(request);
    }

    @GetMapping("/{id}")
    public CompareTaskView get(@PathVariable("id") String id) {
        return compare.get(id);
    }

    @PostMapping("/{id}/cancel")
    public CompareTaskView cancel(@PathVariable("id") String id) {
        return compare.cancel(id);
    }

    /** 指定平台的表清单，供左右两侧选择目标表（不受当前激活平台影响）。 */
    @GetMapping("/tables")
    public Object tables(@RequestParam(value = "cluster", required = false) String cluster) {
        return registry.runtime(cluster).catalog().list();
    }

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("defaultMaxRows", 200_000);
        result.put("maxRows", 1_000_000);
        result.put("defaultSampleLimit", 10);
        result.put("maxSampleLimit", 50);
        return result;
    }
}
