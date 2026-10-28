package com.kuducrud.service.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kuducrud.config.KuduProperties;
import com.kuducrud.kudu.KuduConnectionManager;
import com.kuducrud.model.ClusterConfig;
import com.kuducrud.service.webui.MasterPageParser.MasterRow;
import com.kuducrud.service.webui.MasterPageParser.TableStatsRow;
import com.kuducrud.service.webui.MasterPageParser.TabletServerSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 访问 Kudu master Web UI（RPC 端口 + offset），用于取拓扑、存活与表统计。每个平台一个实例。
 * <p>该构建没有 /api/v1 接口，只能读 HTML 表格与 /metrics?raw。
 * 所有异常都被吞掉并降级，不允许拖垮状态接口。
 */
public class MasterWebUiClient {

    private static final Logger log = LoggerFactory.getLogger(MasterWebUiClient.class);
    private static final long SNAPSHOT_TTL_MS = 5_000;
    private static final long TOTAL_BUDGET_MS = 7_000;

    private final KuduProperties props;
    private final ClusterConfig config;
    private final KuduConnectionManager connection;
    private final ObjectMapper mapper;
    private final HttpClient http;

    private volatile Snapshot cached;
    private volatile long cachedAt;

    public MasterWebUiClient(ClusterConfig config, KuduConnectionManager connection,
                             KuduProperties props, ObjectMapper mapper) {
        this.config = config;
        this.props = props;
        this.connection = connection;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getWebUi().getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public record Snapshot(boolean available, String leaderWebUrl, List<MasterRow> masters,
                           TabletServerSnapshot tabletServers, Map<String, TableStatsRow> tableStats,
                           String error) {

        public static Snapshot unavailable(String error) {
            return new Snapshot(false, null, List.of(), null, Map.of(), error);
        }
    }

    public Snapshot snapshot() {
        if (!config.webUi(props.getWebUi().isEnabled())) {
            return Snapshot.unavailable("master Web UI 已关闭（可在「Kudu 平台」里为该平台开启）");
        }
        Snapshot local = cached;
        if (local != null && System.currentTimeMillis() - cachedAt < SNAPSHOT_TTL_MS) {
            return local;
        }
        synchronized (this) {
            if (cached != null && System.currentTimeMillis() - cachedAt < SNAPSHOT_TTL_MS) {
                return cached;
            }
            Snapshot fresh = load();
            cached = fresh;
            cachedAt = System.currentTimeMillis();
            return fresh;
        }
    }

    /** 表统计（来自 leader 的 /metrics?raw），不可用时返回空 Map。 */
    public Map<String, TableStatsRow> tableStats() {
        return snapshot().tableStats();
    }

    private Snapshot load() {
        long start = System.currentTimeMillis();
        List<String> candidates = candidates();
        if (candidates.isEmpty()) {
            return Snapshot.unavailable("没有可用的 master Web UI 地址");
        }
        String lastError = null;
        String base = null;
        List<MasterRow> masters = List.of();
        for (String candidate : candidates) {
            if (System.currentTimeMillis() - start > TOTAL_BUDGET_MS) {
                lastError = "master Web UI 响应超时";
                break;
            }
            try {
                String html = get(candidate + "/masters");
                List<MasterRow> parsed = MasterPageParser.parseMasters(html);
                if (!parsed.isEmpty()) {
                    base = candidate;
                    masters = parsed;
                    break;
                }
                lastError = "无法解析 /masters 页面";
            } catch (Exception e) {
                lastError = candidate + " → " + shortMessage(e);
            }
        }
        if (base == null) {
            return Snapshot.unavailable(lastError == null ? "master Web UI 不可达" : lastError);
        }

        String scheme = props.getWebUi().getScheme();
        String leaderUrl = base;
        for (MasterRow master : masters) {
            if ("LEADER".equalsIgnoreCase(master.role()) && master.httpAddress() != null) {
                leaderUrl = scheme + "://" + master.httpAddress();
                break;
            }
        }

        TabletServerSnapshot tabletServers = null;
        try {
            tabletServers = MasterPageParser.parseTabletServers(get(leaderUrl + "/tablet-servers"));
        } catch (Exception e) {
            log.debug("read /tablet-servers failed: {}", shortMessage(e));
        }

        Map<String, TableStatsRow> stats = new LinkedHashMap<>();
        try {
            stats = MasterPageParser.parseTableMetrics(get(leaderUrl + "/metrics?raw"), mapper);
        } catch (Exception e) {
            log.debug("read /metrics failed: {}", shortMessage(e));
        }

        return new Snapshot(true, leaderUrl, masters, tabletServers, stats, null);
    }

    private List<String> candidates() {
        String scheme = props.getWebUi().getScheme();
        List<String> result = new ArrayList<>();
        for (String raw : props.getWebUi().getBaseUrls()) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String url = raw.trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = scheme + "://" + url;
            }
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            result.add(url);
        }
        for (String master : KuduConnectionManager.normalize(connection.masters())) {
            int idx = master.lastIndexOf(':');
            String host = master.substring(0, idx);
            int port;
            try {
                port = Integer.parseInt(master.substring(idx + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            String url = scheme + "://" + host + ":" + (port + config.webUiPortOffset(props.getWebUi().getPortOffset()));
            if (!result.contains(url)) {
                result.add(url);
            }
        }
        return result;
    }

    private String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(props.getWebUi().getReadTimeoutMs()))
                .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static String shortMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        if (msg == null) {
            msg = cur.getClass().getSimpleName();
        }
        return msg.length() > 160 ? msg.substring(0, 160) : msg;
    }
}
