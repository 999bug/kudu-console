package com.kuducrud.service;

import com.kuducrud.kudu.KuduConnectionManager;
import com.kuducrud.kudu.TableNameUtil;
import com.kuducrud.model.TableSummary;
import com.kuducrud.service.webui.MasterPageParser.TableStatsRow;
import com.kuducrud.service.webui.MasterWebUiClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 表清单：Kudu 无 catalog，按表名解析出 db 后分组。
 */
public class TableCatalogService {

    private final KuduConnectionManager connection;
    private final MasterWebUiClient webUi;

    public TableCatalogService(KuduConnectionManager connection, MasterWebUiClient webUi) {
        this.connection = connection;
        this.webUi = webUi;
    }

    public record TableGroup(String db, int tableCount, List<TableSummary> tables) {
    }

    public record TableListResponse(List<TableGroup> dbs, int totalTables, String statsSource) {
    }

    public TableListResponse list() {
        List<String> names = connection.listTables();
        Map<String, TableStatsRow> stats = webUi.tableStats();

        Map<String, List<TableSummary>> grouped = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : names) {
            String db = TableNameUtil.dbOf(name);
            TableStatsRow row = stats.get(name);
            TableSummary summary = new TableSummary(
                    name,
                    TableNameUtil.shortNameOf(name),
                    db,
                    TableNameUtil.isImpalaManaged(name),
                    row == null ? null : row.columnCount(),
                    row == null ? null : row.liveRowCount(),
                    row == null ? null : row.onDiskSize());
            grouped.computeIfAbsent(db, key -> new ArrayList<>()).add(summary);
        }

        List<TableGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<TableSummary>> entry : grouped.entrySet()) {
            List<TableSummary> tables = entry.getValue();
            tables.sort(Comparator.comparing(TableSummary::name, String.CASE_INSENSITIVE_ORDER));
            groups.add(new TableGroup(entry.getKey(), tables.size(), tables));
        }
        return new TableListResponse(groups, names.size(), stats.isEmpty() ? "NONE" : "MASTER_METRICS");
    }

    /** 单表统计：优先 master metrics，取不到则返回 null。 */
    public TableStatsRow statsOf(String table) {
        return webUi.tableStats().get(table);
    }

    public Map<String, TableStatsRow> allStats() {
        return new LinkedHashMap<>(webUi.tableStats());
    }
}
