package com.kuducrud.web;

import com.kuducrud.config.ClusterRegistry;
import com.kuducrud.model.JobView;
import com.kuducrud.model.TestActionSpec;
import com.kuducrud.model.TestRunRecord;
import com.kuducrud.model.TestSchedule;
import com.kuducrud.model.TestScheduleRequest;
import com.kuducrud.service.TestActionRules;
import com.kuducrud.service.TestScheduleService;
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
@RequestMapping("/api/test")
public class TestController {

    private final ClusterRegistry registry;
    private final TestScheduleService schedules;

    public TestController(ClusterRegistry registry, TestScheduleService schedules) {
        this.registry = registry;
        this.schedules = schedules;
    }

    public record RunRequest(List<String> tables, TestActionSpec actions) {
    }

    public record ToggleRequest(Boolean enabled) {
    }

    /** 立即执行一次（不保存为定时任务）。 */
    @PostMapping("/run")
    public JobView run(@RequestBody RunRequest request) {
        if (request == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        return registry.current().testActions().submit(request.tables(), request.actions());
    }

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("maxTables", TestActionRules.MAX_TABLES);
        result.put("maxCount", TestActionSpec.MAX_COUNT);
        result.put("defaultCount", TestActionSpec.DEFAULT_COUNT);
        result.put("columnTypes", TestActionSpec.COLUMN_TYPES);
        result.put("columnModes", TestActionSpec.COLUMN_MODES);
        result.put("minIntervalSeconds", 60);
        result.put("defaultIntervalSeconds", 300);
        return result;
    }

    @GetMapping("/schedules")
    public List<TestSchedule> schedules() {
        return schedules.list();
    }

    @PostMapping("/schedules")
    public TestSchedule create(@RequestBody TestScheduleRequest request) {
        return schedules.create(registry.current().id(), request);
    }

    @PutMapping("/schedules/{id}")
    public TestSchedule update(@PathVariable("id") String id, @RequestBody TestScheduleRequest request) {
        return schedules.update(id, request);
    }

    @DeleteMapping("/schedules/{id}")
    public Map<String, Object> delete(@PathVariable("id") String id) {
        schedules.delete(id);
        return Map.of("ok", true);
    }

    @PostMapping("/schedules/{id}/run")
    public JobView runNow(@PathVariable("id") String id) {
        return schedules.runNow(id);
    }

    @PostMapping("/schedules/{id}/toggle")
    public TestSchedule toggle(@PathVariable("id") String id,
                               @RequestBody(required = false) ToggleRequest request) {
        if (request == null || request.enabled() == null) {
            throw ApiException.badRequest("enabled 不能为空");
        }
        return schedules.toggle(id, request.enabled());
    }

    @GetMapping("/schedules/{id}/runs")
    public List<TestRunRecord> runs(@PathVariable("id") String id) {
        return schedules.runs(id);
    }
}
