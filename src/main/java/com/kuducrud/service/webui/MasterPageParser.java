package com.kuducrud.service.webui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 master Web UI 的 HTML 表格与 /metrics?raw JSON。
 * <p>该 Kudu 构建没有 /api/v1 JSON 接口，只能从页面表格取拓扑。
 */
public final class MasterPageParser {

    private static final Pattern RPC = Pattern.compile(
            "rpc_addresses\\s*\\{\\s*host:\\s*\"([^\"]+)\"\\s*port:\\s*(\\d+)");
    private static final Pattern HTTP = Pattern.compile(
            "http_addresses\\s*\\{\\s*host:\\s*\"([^\"]+)\"\\s*port:\\s*(\\d+)");
    private static final Pattern VERSION = Pattern.compile("software_version:\\s*\"([^\"]+)\"");
    private static final Pattern HTTPS = Pattern.compile("https_enabled:\\s*(true|false)");

    private MasterPageParser() {
    }

    public record MasterRow(String uuid, String clusterId, String role, String startTime,
                            String rpcHost, Integer rpcPort, String httpHost, Integer httpPort,
                            String version, Boolean httpsEnabled) {
        public String rpcAddress() {
            return rpcHost == null ? null : rpcHost + ":" + rpcPort;
        }

        public String httpAddress() {
            return httpHost == null ? null : httpHost + ":" + httpPort;
        }
    }

    public record TabletServerRow(String uuid, String location, Double heartbeatAgeSec, String startTime,
                                  String rpcHost, Integer rpcPort, String httpHost, Integer httpPort,
                                  String version, boolean live) {
        public String address() {
            return rpcHost == null ? null : rpcHost + ":" + rpcPort;
        }

        public String httpAddress() {
            return httpHost == null ? null : httpHost + ":" + httpPort;
        }
    }

    public record TabletServerSnapshot(List<TabletServerRow> rows, int liveCount, int deadCount,
                                       Map<String, Integer> liveByVersion) {
    }

    public record TableStatsRow(String table, Long liveRowCount, Long onDiskSize, Integer columnCount) {
    }

    // ------------------------------------------------------------------
    // /masters
    // ------------------------------------------------------------------

    public static List<MasterRow> parseMasters(String html) {
        List<MasterRow> result = new ArrayList<>();
        Document doc = Jsoup.parse(html == null ? "" : html);
        for (Element table : doc.select("table")) {
            Elements headers = table.select("thead th");
            if (!headers.eachText().contains("Registration")) {
                continue;
            }
            for (Element tr : table.select("tbody > tr")) {
                Elements tds = tr.select("> td");
                if (tds.size() < 5) {
                    continue;
                }
                String uuid = tds.get(0).text().trim();
                String clusterId = tds.get(1).text().trim();
                String role = tds.get(2).text().trim();
                String startTime = tds.get(3).text().trim();
                String registration = tds.get(4).select("pre code").text();
                if (registration.isEmpty()) {
                    registration = tds.get(4).text();
                }
                Matcher rpc = RPC.matcher(registration);
                Matcher http = HTTP.matcher(registration);
                Matcher version = VERSION.matcher(registration);
                Matcher https = HTTPS.matcher(registration);
                String rpcHost = null;
                Integer rpcPort = null;
                if (rpc.find()) {
                    rpcHost = rpc.group(1);
                    rpcPort = parsePort(rpc.group(2));
                }
                String httpHost = null;
                Integer httpPort = null;
                if (http.find()) {
                    httpHost = http.group(1);
                    httpPort = parsePort(http.group(2));
                }
                result.add(new MasterRow(uuid, clusterId, role, startTime,
                        rpcHost, rpcPort, httpHost, httpPort,
                        version.find() ? version.group(1) : null,
                        https.find() ? Boolean.valueOf(https.group(1)) : null));
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // /tablet-servers
    // ------------------------------------------------------------------

    public static TabletServerSnapshot parseTabletServers(String html) {
        Document doc = Jsoup.parse(html == null ? "" : html);
        List<TabletServerRow> rows = new ArrayList<>();
        Map<String, Integer> liveByVersion = new LinkedHashMap<>();
        int live = 0;
        int dead = 0;

        for (Element table : doc.select("table")) {
            Elements headers = table.select("thead th");
            List<String> head = headers.eachText();
            if (head.contains("Count (Live)")) {
                for (Element tr : table.select("tbody > tr")) {
                    Elements tds = tr.select("> td");
                    if (tds.size() < 3) {
                        continue;
                    }
                    int l = parseIntOrZero(tds.get(1).text());
                    int d = parseIntOrZero(tds.get(2).text());
                    live += l;
                    dead += d;
                    liveByVersion.put(tds.get(0).text().trim(), l);
                }
            } else if (head.contains("Time since heartbeat")) {
                boolean isLive = isLiveSection(table);
                for (Element tr : table.select("tbody > tr")) {
                    Elements tds = tr.select("> td");
                    if (tds.size() < 5) {
                        continue;
                    }
                    String uuid = tds.get(0).text().trim();
                    String location = tds.get(1).text().trim();
                    if ("<none>".equals(location)) {
                        location = null;
                    }
                    Double heartbeat = parseSeconds(tds.get(2).text());
                    String startTime = tds.get(3).text().trim();
                    String registration = tds.get(4).select("pre code").text();
                    if (registration.isEmpty()) {
                        registration = tds.get(4).text();
                    }
                    Matcher rpc = RPC.matcher(registration);
                    Matcher http = HTTP.matcher(registration);
                    Matcher version = VERSION.matcher(registration);
                    String rpcHost = null;
                    Integer rpcPort = null;
                    if (rpc.find()) {
                        rpcHost = rpc.group(1);
                        rpcPort = parsePort(rpc.group(2));
                    }
                    String httpHost = null;
                    Integer httpPort = null;
                    if (http.find()) {
                        httpHost = http.group(1);
                        httpPort = parsePort(http.group(2));
                    }
                    rows.add(new TabletServerRow(uuid, location, heartbeat, startTime,
                            rpcHost, rpcPort, httpHost, httpPort,
                            version.find() ? version.group(1) : null, isLive));
                }
            }
        }
        if (live == 0 && dead == 0) {
            live = (int) rows.stream().filter(TabletServerRow::live).count();
            dead = rows.size() - live;
        }
        return new TabletServerSnapshot(rows, live, dead, liveByVersion);
    }

    private static boolean isLiveSection(Element table) {
        Element prev = table.previousElementSibling();
        while (prev != null && !prev.tagName().matches("h[1-6]")) {
            prev = prev.previousElementSibling();
        }
        return prev == null || !prev.text().toLowerCase().contains("dead");
    }

    // ------------------------------------------------------------------
    // /metrics?raw
    // ------------------------------------------------------------------

    public static Map<String, TableStatsRow> parseTableMetrics(String json, ObjectMapper mapper) {
        Map<String, TableStatsRow> result = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            return result;
        }
        if (!root.isArray()) {
            return result;
        }
        for (JsonNode entity : root) {
            if (!"table".equals(entity.path("type").asText())) {
                continue;
            }
            String table = entity.path("attributes").path("table_name").asText(null);
            if (table == null || table.isEmpty()) {
                continue;
            }
            Long rows = null;
            Long size = null;
            Integer columns = null;
            for (JsonNode metric : entity.path("metrics")) {
                String name = metric.path("name").asText("");
                JsonNode value = metric.path("value");
                switch (name) {
                    case "live_row_count" -> rows = value.asLong();
                    case "on_disk_size" -> size = value.asLong();
                    case "column_count" -> columns = value.asInt();
                    default -> {
                    }
                }
            }
            result.put(table, new TableStatsRow(table, rows, size, columns));
        }
        return result;
    }

    private static Integer parsePort(String text) {
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseIntOrZero(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** "0.1s" → 0.1；"12.5s" → 12.5 */
    private static Double parseSeconds(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.endsWith("s")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        try {
            return Double.valueOf(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
