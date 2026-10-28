package com.kuducrud.model;

import java.util.List;

/**
 * 集群状态：client 侧事实 + master Web UI 侧事实，Web UI 不可达时降级。
 */
public record ClusterStatus(
        String platformName,
        boolean connected,
        String clusterId,
        String kuduVersion,
        String masterAddresses,
        List<MasterInfo> masters,
        List<TabletServerInfo> tabletServers,
        Counts counts,
        WebUiStatus webUi,
        long elapsedMs
) {

    public record MasterInfo(
            String uuid,
            String role,
            String rpcAddress,
            String httpAddress,
            String startTime,
            String version,
            String webUrl,
            Boolean selfReported
    ) {
    }

    public record TabletServerInfo(
            String uuid,
            String address,
            String httpAddress,
            Boolean live,
            Double heartbeatAgeSec,
            String startTime,
            String location,
            String version,
            String webUrl
    ) {
    }

    public record Counts(Integer tables, Integer tabletServers, Integer liveTabletServers,
                         Integer deadTabletServers) {
    }

    public record WebUiStatus(boolean available, boolean degraded, String leaderWebUrl, String error) {
    }
}
