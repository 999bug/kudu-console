package com.kuducrud.kudu;

import com.kuducrud.web.ApiException;
import org.apache.kudu.client.KuduClient;
import org.apache.kudu.client.KuduTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.kuducrud.kudu.KuduErrors.translate;

/**
 * 单个平台的连接持有者：懒建连、表对象缓存。每个平台一个实例，由 {@code ClusterRegistry} 管理生命周期。
 * <p>KuduClient / KuduTable 均为线程安全，可全局共享；KuduSession 不是，见 {@link KuduSessionTemplate}。
 */
public class KuduConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(KuduConnectionManager.class);

    private final String masters;
    private final int operationTimeoutMs;
    private final int adminTimeoutMs;
    private final int connectTimeoutMs;

    private final Map<String, KuduTable> tableCache = new ConcurrentHashMap<>();
    private volatile Conn current;

    public KuduConnectionManager(String masters, int operationTimeoutMs, int adminTimeoutMs, int connectTimeoutMs) {
        this.masters = masters;
        this.operationTimeoutMs = operationTimeoutMs;
        this.adminTimeoutMs = adminTimeoutMs;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    /** 当前连接（含建连时间），未建连时懒初始化。 */
    public Conn conn() {
        Conn local = current;
        if (local == null) {
            synchronized (this) {
                local = current;
                if (local == null) {
                    local = build();
                    current = local;
                    log.info("kudu client created, masters={}", local.masters());
                }
            }
        }
        return local;
    }

    public KuduClient client() {
        return conn().client();
    }

    public String masters() {
        return masters;
    }

    /**
     * 打开表（带缓存）。注意 schema 是打开瞬间的快照，DDL 后必须 {@link #invalidate(String)}。
     */
    public KuduTable table(String name) {
        KuduTable cached = tableCache.get(name);
        if (cached != null) {
            return cached;
        }
        try {
            KuduTable table = client().openTable(name);
            tableCache.put(name, table);
            return table;
        } catch (Exception e) {
            throw translate(e, "打开表 " + name + " 失败");
        }
    }

    public void invalidate(String name) {
        if (name != null) {
            tableCache.remove(name);
        }
    }

    public void invalidateAll() {
        tableCache.clear();
    }

    public boolean tableExists(String name) {
        try {
            return client().tableExists(name);
        } catch (Exception e) {
            throw translate(e, "探测表 " + name + " 失败");
        }
    }

    public List<String> listTables() {
        try {
            return new ArrayList<>(client().getTablesList().getTablesList());
        } catch (Exception e) {
            throw translate(e, "列举表失败");
        }
    }

    private Conn build() {
        List<String> normalized = normalize(masters);
        if (normalized.isEmpty()) {
            throw ApiException.badRequest("masters 为空，请在「Kudu 平台」里填写 master 地址");
        }
        String joined = String.join(",", normalized);
        // 先做 TCP 探活：地址写错时立即失败，避免客户端在 RPC 上等满 operation timeout
        if (!tcpReachable(normalized, 1500)) {
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "KUDU_UNAVAILABLE",
                    "master 地址不可达（" + joined + "），请检查网络与端口");
        }
        KuduClient client = builder(joined);
        try {
            client.getTablesList(); // 探活，失败立刻抛
        } catch (Exception e) {
            try {
                client.close();
            } catch (Exception ignored) {
                // 建连失败时关闭异常无意义
            }
            throw translate(e, "连接 Kudu 失败（" + joined + "）");
        }
        return new Conn(client, joined, System.currentTimeMillis());
    }

    /** 用候选 master 地址探活：临时建一个客户端，不改变任何平台状态。 */
    public static ProbeResult probe(String masters, int operationTimeoutMs, int adminTimeoutMs,
                                    int connectTimeoutMs) {
        long start = System.currentTimeMillis();
        List<String> normalized = normalize(masters);
        if (normalized.isEmpty()) {
            return new ProbeResult(false, -1, 0, "地址格式应为 host:port[,host:port]");
        }
        if (!tcpReachable(normalized, 1500)) {
            return new ProbeResult(false, -1, System.currentTimeMillis() - start,
                    "master 端口不可达：" + String.join(",", normalized));
        }
        try (KuduClient probeClient = new KuduClient.KuduClientBuilder(String.join(",", normalized))
                .defaultOperationTimeoutMs(operationTimeoutMs)
                .defaultAdminOperationTimeoutMs(adminTimeoutMs)
                .connectionNegotiationTimeoutMs(connectTimeoutMs)
                .build()) {
            int tables = probeClient.getTablesList().getTablesList().size();
            return new ProbeResult(true, tables, System.currentTimeMillis() - start, null);
        } catch (Exception e) {
            return new ProbeResult(false, -1, System.currentTimeMillis() - start,
                    e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** 任意一个 master 端口可连即认为网络可达。 */
    private static boolean tcpReachable(List<String> addresses, int timeoutMs) {
        for (String address : addresses) {
            int idx = address.lastIndexOf(':');
            if (idx <= 0) {
                continue;
            }
            String host = address.substring(0, idx);
            int port;
            try {
                port = Integer.parseInt(address.substring(idx + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
                return true;
            } catch (Exception ignored) {
                // 继续尝试下一个地址
            }
        }
        return false;
    }

    private KuduClient builder(String masters) {
        return new KuduClient.KuduClientBuilder(masters)
                .defaultOperationTimeoutMs(operationTimeoutMs)
                .defaultAdminOperationTimeoutMs(adminTimeoutMs)
                .connectionNegotiationTimeoutMs(connectTimeoutMs)
                .build();
    }

    /** 拆分并校验 host:port 列表。 */
    public static List<String> normalize(String masters) {
        List<String> result = new ArrayList<>();
        if (masters == null) {
            return result;
        }
        for (String raw : masters.split("[,;\\s]+")) {
            String item = raw.trim();
            if (item.isEmpty()) {
                continue;
            }
            int idx = item.lastIndexOf(':');
            if (idx <= 0 || idx == item.length() - 1) {
                continue;
            }
            try {
                int port = Integer.parseInt(item.substring(idx + 1));
                if (port <= 0 || port > 65535) {
                    continue;
                }
            } catch (NumberFormatException e) {
                continue;
            }
            result.add(item);
        }
        return result;
    }

    public void shutdown() {
        Conn local = current;
        current = null;
        invalidateAll();
        if (local != null) {
            try {
                local.client().close();
            } catch (Exception e) {
                log.warn("close kudu client failed: {}", e.getMessage());
            }
        }
    }

    public record Conn(KuduClient client, String masters, long createdAt) {
    }

    public record ProbeResult(boolean ok, int tableCount, long elapsedMs, String error) {
    }
}
