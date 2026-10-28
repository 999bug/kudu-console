package com.kuducrud.web;

import com.kuducrud.config.ClusterRegistry;
import com.kuducrud.job.JobHandle;
import com.kuducrud.job.JobRegistry;
import com.kuducrud.model.JobView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobRegistry jobs;
    private final ClusterRegistry registry;

    public JobController(JobRegistry jobs, ClusterRegistry registry) {
        this.jobs = jobs;
        this.registry = registry;
    }

    public record TruncateRequest(String confirm, Boolean acknowledge) {
    }

    @PostMapping("/truncate")
    public JobView truncate(@RequestParam("table") String table,
                            @RequestBody(required = false) TruncateRequest request) {
        return registry.current().truncate().submit(table,
                request == null ? null : request.confirm(),
                request == null ? null : request.acknowledge());
    }

    @GetMapping
    public List<JobView> list() {
        return jobs.list();
    }

    @GetMapping("/{id}")
    public JobView get(@PathVariable("id") String id) {
        return jobs.get(id).toView();
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable("id") String id) {
        JobHandle handle = jobs.get(id);
        handle.requestCancel();
        return Map.of("ok", true, "state", handle.state());
    }
}
