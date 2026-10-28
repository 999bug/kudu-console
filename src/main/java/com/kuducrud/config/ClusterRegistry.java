package com.kuducrud.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kuducrud.job.JobRegistry;
import com.kuducrud.kudu.KuduConnectionManager;
import com.kuducrud.model.ClusterConfig;
import com.kuducrud.web.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 平台注册表：维护多个 Kudu 平台的配置与运行时，负责持久化、切换当前平台、增删改。
 * <p>请求级解析顺序：{@code X-Kudu-Cluster} 头 → {@code cluster} 查询参数 → 当前激活平台。
 */
@Component
public class ClusterRegistry {

    private static final Logger log = LoggerFactory.getLogger(ClusterRegistry.class);
    public static final String HEADER = "X-Kudu-Cluster";
    public static final String PARAM = "cluster";

    private final KuduProperties props;
    private final ObjectMapper mapper;
    private final JobRegistry jobs;
    private final ClusterStore store;
    private final Map<String, ClusterRuntime> runtimes = new ConcurrentHashMap<>();

    private volatile ClusterStore.State state;

    public ClusterRegistry(KuduProperties props, ObjectMapper mapper, JobRegistry jobs, ClusterStore store) {
        this.props = props;
        this.mapper = mapper;
        this.jobs = jobs;
        this.store = store;
    }

    @PostConstruct
    public void init() {
        ClusterConfig seed = new ClusterConfig("default", "默认集群", props.getMasters(), null, null, null, null);
        state = store.load(seed);
        log.info("kudu platforms loaded: {} (active={})", state.clusters().size(), state.activeId());
    }

    public List<ClusterConfig> list() {
        return state.clusters();
    }

    public String activeId() {
        return state.activeId();
    }

    public List<ClusterSummary> summaries() {
        List<ClusterSummary> result = new ArrayList<>();
        for (ClusterConfig config : state.clusters()) {
            result.add(new ClusterSummary(config, config.id().equals(state.activeId())));
        }
        return result;
    }

    public ClusterConfig config(String id) {
        return state.clusters().stream()
                .filter(c -> c.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "CLUSTER_NOT_FOUND", "平台不存在：" + id));
    }

    /** 取运行时，首次访问才建连，不会因为某个平台不可达拖慢其它平台。 */
    public ClusterRuntime runtime(String idOrNull) {
        String id = (idOrNull == null || idOrNull.isBlank()) ? state.activeId() : idOrNull;
        ClusterConfig config = config(id);
        return runtimes.computeIfAbsent(id, key -> {
            log.info("creating runtime for platform {} ({})", config.name(), config.masters());
            return new ClusterRuntime(config, props, mapper, jobs);
        });
    }

    /** 从当前 HTTP 请求解析平台；无请求上下文时回落到激活平台。 */
    public ClusterRuntime current() {
        String requested = null;
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            requested = servlet.getRequest().getHeader(HEADER);
            if (requested == null || requested.isBlank()) {
                requested = servlet.getRequest().getParameter(PARAM);
            }
        }
        return runtime(requested);
    }

    public ClusterConfig create(ClusterRequest request) {
        validate(request);
        String id = newId();
        ClusterConfig config = new ClusterConfig(id, request.name().trim(), normalized(request.masters()),
                request.operationTimeoutMs(), request.adminTimeoutMs(), request.webUiEnabled(),
                request.webUiPortOffset());
        List<ClusterConfig> clusters = new ArrayList<>(state.clusters());
        clusters.add(config);
        save(new ClusterStore.State(state.activeId(), List.copyOf(clusters)));
        log.info("platform created: {} ({})", config.name(), config.masters());
        return config;
    }

    public ClusterConfig update(String id, ClusterRequest request) {
        validate(request);
        ClusterConfig existing = config(id);
        ClusterConfig updated = new ClusterConfig(existing.id(), request.name().trim(), normalized(request.masters()),
                request.operationTimeoutMs(), request.adminTimeoutMs(), request.webUiEnabled(),
                request.webUiPortOffset());
        List<ClusterConfig> clusters = new ArrayList<>();
        for (ClusterConfig item : state.clusters()) {
            clusters.add(item.id().equals(id) ? updated : item);
        }
        save(new ClusterStore.State(state.activeId(), List.copyOf(clusters)));
        // 配置变了，丢掉旧运行时（连接、缓存都作废）
        ClusterRuntime old = runtimes.remove(id);
        if (old != null) {
            old.close();
        }
        log.info("platform updated: {} ({})", updated.name(), updated.masters());
        return updated;
    }

    public void delete(String id) {
        if (state.clusters().size() <= 1) {
            throw ApiException.badRequest("至少保留一个平台，不能删除最后一个");
        }
        config(id);
        List<ClusterConfig> clusters = new ArrayList<>();
        for (ClusterConfig item : state.clusters()) {
            if (!item.id().equals(id)) {
                clusters.add(item);
            }
        }
        String active = state.activeId().equals(id) ? clusters.get(0).id() : state.activeId();
        save(new ClusterStore.State(active, List.copyOf(clusters)));
        ClusterRuntime old = runtimes.remove(id);
        if (old != null) {
            old.close();
        }
        log.info("platform deleted: {}", id);
    }

    public void activate(String id) {
        config(id);
        if (id.equals(state.activeId())) {
            return;
        }
        save(new ClusterStore.State(id, state.clusters()));
        log.info("platform activated: {}", id);
    }

    public KuduConnectionManager.ProbeResult probe(String masters) {
        return KuduConnectionManager.probe(masters, props.getOperationTimeoutMs(), props.getAdminTimeoutMs(),
                props.getConnectTimeoutMs());
    }

    private void save(ClusterStore.State next) {
        state = next;
        store.save(next);
    }

    private void validate(ClusterRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw ApiException.badRequest("平台名称不能为空");
        }
        if (request.name().trim().length() > 64) {
            throw ApiException.badRequest("平台名称不能超过 64 个字符");
        }
        if (KuduConnectionManager.normalize(request.masters()).isEmpty()) {
            throw ApiException.badRequest("master 地址格式应为 host:port[,host:port]");
        }
    }

    private static String normalized(String masters) {
        return String.join(",", KuduConnectionManager.normalize(masters));
    }

    private static String newId() {
        return "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @PreDestroy
    public void closeAll() {
        runtimes.values().forEach(ClusterRuntime::close);
        runtimes.clear();
    }

    public record ClusterRequest(String name, String masters, Integer operationTimeoutMs,
                                 Integer adminTimeoutMs, Boolean webUiEnabled, Integer webUiPortOffset) {
    }

    public record ClusterSummary(ClusterConfig config, boolean active) {
    }
}
