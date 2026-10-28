package com.kuducrud.service;

import com.kuducrud.config.KuduProperties;
import com.kuducrud.kudu.KuduConnectionManager;
import com.kuducrud.kudu.PartitionHelper;
import com.kuducrud.kudu.SchemaMapper;
import com.kuducrud.kudu.TableNameUtil;
import com.kuducrud.model.ColumnDef;
import com.kuducrud.model.PartitionDef;
import com.kuducrud.model.TableDetail;
import com.kuducrud.model.TableStats;
import com.kuducrud.service.webui.MasterPageParser.TableStatsRow;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Schema;
import org.apache.kudu.client.KuduTable;

import java.util.List;

/**
 * 表结构：列、主键、副本数、分区、统计。
 */
public class TableSchemaService {

    private final KuduConnectionManager connection;
    private final TableCatalogService catalog;
    private final KuduProperties props;

    public TableSchemaService(KuduConnectionManager connection, TableCatalogService catalog, KuduProperties props) {
        this.connection = connection;
        this.catalog = catalog;
        this.props = props;
    }

    public TableDetail detail(String name) {
        KuduTable table = connection.table(name);
        Schema schema = table.getSchema();
        List<ColumnDef> columns = schema.getColumns().stream().map(SchemaMapper::toDef).toList();
        List<String> primaryKey = schema.getPrimaryKeyColumns().stream().map(ColumnSchema::getName).toList();
        PartitionDef partition = PartitionHelper.capture(table, props.getOperationTimeoutMs());
        return new TableDetail(
                name,
                TableNameUtil.shortNameOf(name),
                TableNameUtil.dbOf(name),
                TableNameUtil.isImpalaManaged(name),
                TableNameUtil.isSystemTable(name),
                tableId(table),
                table.getNumReplicas(),
                null,
                null,
                primaryKey,
                columns,
                partition,
                stats(name));
    }

    private TableStats stats(String name) {
        TableStatsRow row = catalog.statsOf(name);
        if (row != null && (row.liveRowCount() != null || row.onDiskSize() != null)) {
            return new TableStats(row.liveRowCount(), row.onDiskSize(), "MASTER_METRICS");
        }
        return null;
    }

    private static String tableId(KuduTable table) {
        try {
            return table.getTableId();
        } catch (Exception e) {
            return null;
        }
    }
}
