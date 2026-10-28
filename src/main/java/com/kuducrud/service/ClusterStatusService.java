package com.kuducrud.service;

import com.kuducrud.kudu.KuduConnectionManager;
import com.kuducrud.model.ClusterConfig;
import com.kuducrud.model.ClusterStatus;
import com.kuducrud.model.ClusterStatus.Counts;
import com.kuducrud.model.ClusterStatus.MasterInfo;
import com.kuducrud.model.ClusterStatus.TabletServerInfo;
import com.kuducrud.model.ClusterStatus.WebUiStatus;
import com.kuducrud.service.webui.MasterPageParser.MasterRow;
import com.kuducrud.service.webui.MasterPageParser.TabletServerRow;
import com.kuducrud.service.webui.MasterPageParser.TabletServerSnapshot;
import com.kuducrud.service.webui.MasterWebUiClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 集群状态：client 侧事实为准，master Web UI 用于补角色/存活/版本，Web UI 不可达时降级。
 */
public class ClusterStatusService {

    private final KuduConnectionManager connection;
    private final MasterWebUiClient webUi;
    private final ClusterConfig config;

    public ClusterStatusService(KuduConnectionManager connection, MasterWebUiClient webUi,
                                ClusterConfig config) {
        this.connection = connection;
        this.webUi = webUi;
        this.config = config;
    }

    public ClusterStatus status() {
        long started = System.currentTimeMillis();
        boolean connected = true;
        String clusterId = null;
        Integer tableCount = null;
        try {
            clusterId = connection.client().getClusterId();
            tableCount = connection.listTables().size();
        } catch (Exception e) {
            connected = false;
        }

        MasterWebUiClient.Snapshot snapshot = connected
                ? webUi.snapshot()
                : MasterWebUiClient.Snapshot.unavailable("Kudu RPC 不可达，跳过 Web UI 探测");
        List<MasterInfo> masters = new ArrayList<>();
        String version = null;
        for (MasterRow row : snapshot.masters()) {
            masters.add(new MasterInfo(row.uuid(), row.role(), row.rpcAddress(), row.httpAddress(),
                    row.startTime(), row.version(), webUrl(row.httpHost(), row.httpPort(), row.httpsEnabled()),
                    true));
            if (version == null) {
                version = row.version();
            }
        }
        if (masters.isEmpty()) {
            for (String address : KuduConnectionManager.normalize(config.masters())) {
                masters.add(new MasterInfo(null, null, address, null, null, null, null, false));
            }
        }

        TabletServerSnapshot tsSnapshot = snapshot.tabletServers();
        List<TabletServerInfo> tabletServers = new ArrayList<>();
        if (tsSnapshot != null) {
            for (TabletServerRow row : tsSnapshot.rows()) {
                tabletServers.add(new TabletServerInfo(row.uuid(), row.address(), row.httpAddress(), row.live(),
                        row.heartbeatAgeSec(), row.startTime(), row.location(), row.version(),
                        webUrl(row.httpHost(), row.httpPort(), null)));
            }
        }

        Integer live = tsSnapshot == null ? null : tsSnapshot.liveCount();
        Integer dead = tsSnapshot == null ? null : tsSnapshot.deadCount();
        Counts counts = new Counts(tableCount, tabletServers.isEmpty() ? null : tabletServers.size(), live, dead);

        boolean degraded = snapshot.available() && (tsSnapshot == null || tsSnapshot.rows().isEmpty());
        WebUiStatus webUiStatus = new WebUiStatus(snapshot.available(), degraded, snapshot.leaderWebUrl(),
                snapshot.error());

        return new ClusterStatus(config.name(), connected, clusterId, version, config.masters(), masters,
                tabletServers, counts, webUiStatus, System.currentTimeMillis() - started);
    }

    private static String webUrl(String host, Integer port, Boolean https) {
        if (host == null || port == null) {
            return null;
        }
        String scheme = Boolean.TRUE.equals(https) ? "https" : "http";
        return scheme + "://" + host + ":" + port;
    }
}
