package com.kuducrud.service;

import com.kuducrud.kudu.KuduConnectionManager;
import com.kuducrud.kudu.KuduErrors;
import com.kuducrud.kudu.PartitionHelper;
import com.kuducrud.kudu.SchemaMapper;
import com.kuducrud.model.ColumnDef;
import com.kuducrud.model.CreateTableRequest;
import com.kuducrud.web.ApiException;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Schema;
import org.apache.kudu.client.AlterTableOptions;
import org.apache.kudu.client.CreateTableOptions;
import org.apache.kudu.client.KuduClient;
import org.apache.kudu.client.KuduTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 表级 DDL：建表 / 删表 / 改名 / 加列 / 删列 / 改副本数。
 * <p>所有修改成功后必须失效表缓存，否则详情页会读到旧 schema。
 */
public class DdlService {

    private static final Logger log = LoggerFactory.getLogger(DdlService.class);

    private final KuduConnectionManager connection;

    public DdlService(KuduConnectionManager connection) {
        this.connection = connection;
    }

    public void create(CreateTableRequest request) {
        DdlValidator.validateCreate(request);
        String name = request.name().trim();
        KuduClient client = connection.client();
        if (exists(client, name)) {
            throw ApiException.conflict("TABLE_EXISTS", "表已存在：" + name);
        }

        List<ColumnSchema> columns = request.columns().stream().map(SchemaMapper::toSchema).toList();
        Schema schema = new Schema(columns);

        CreateTableOptions options = new CreateTableOptions();
        options.setNumReplicas(request.numReplicas() == null ? 1 : request.numReplicas());
        options.setWait(true);
        PartitionHelper.apply(options, request.hashPartitions(), request.rangePartitionColumns(),
                request.rangePartitions(), schema);

        try {
            client.createTable(name, schema, options);
        } catch (Exception e) {
            throw translate(e, "建表 " + name + " 失败");
        }
        connection.invalidate(name);
        log.info("table created: {}", name);
    }

    public void drop(String name, String confirm) {
        requireConfirm(name, confirm);
        KuduClient client = connection.client();
        if (!exists(client, name)) {
            throw ApiException.notFound("表不存在：" + name);
        }
        try {
            client.deleteTable(name);
        } catch (Exception e) {
            throw translate(e, "删表 " + name + " 失败");
        }
        connection.invalidate(name);
        log.info("table dropped: {}", name);
    }

    public void rename(String name, String newName) {
        DdlValidator.validateTableName(newName);
        if (name.equals(newName)) {
            throw ApiException.badRequest("新表名与原名相同");
        }
        KuduClient client = connection.client();
        if (!exists(client, name)) {
            throw ApiException.notFound("表不存在：" + name);
        }
        try {
            client.alterTable(name, new AlterTableOptions().renameTable(newName).setWait(true));
        } catch (Exception e) {
            throw translate(e, "重命名表 " + name + " 失败");
        }
        connection.invalidate(name);
        connection.invalidate(newName);
        log.info("table renamed: {} -> {}", name, newName);
    }

    public void addColumn(String name, ColumnDef def) {
        KuduTable table = connection.table(name);
        List<String> existing = table.getSchema().getColumns().stream().map(ColumnSchema::getName).toList();
        DdlValidator.validateAddColumn(existing, def);
        ColumnSchema column = SchemaMapper.toSchema(new ColumnDef(def.name().trim(), def.type(), false,
                def.nullable(), def.precision(), def.scale(), def.length(), def.encoding(), def.compression(),
                def.blockSizeBytes(), def.defaultValue(), def.comment()));
        try {
            connection.client().alterTable(name, new AlterTableOptions().addColumn(column).setWait(true));
        } catch (Exception e) {
            throw translate(e, "加列失败");
        }
        connection.invalidate(name);
        log.info("column added: {}.{}", name, def.name());
    }

    public void dropColumn(String name, String columnName) {
        KuduTable table = connection.table(name);
        Schema schema = table.getSchema();
        ColumnSchema column = schema.getColumn(columnName);
        if (column == null) {
            throw ApiException.notFound("列不存在：" + columnName);
        }
        if (column.isKey()) {
            throw ApiException.badRequest("主键列不能删除（Kudu 不支持修改主键）");
        }
        if (schema.getColumnCount() <= 1) {
            throw ApiException.badRequest("不能删除最后一列");
        }
        try {
            connection.client().alterTable(name, new AlterTableOptions().dropColumn(columnName).setWait(true));
        } catch (Exception e) {
            throw translate(e, "删列失败");
        }
        connection.invalidate(name);
        log.info("column dropped: {}.{}", name, columnName);
    }

    /**
     * 改副本数：1.17 Java 客户端没有 setNumReplicas，只能用反射探测；
     * 不可用时明确返回 UNSUPPORTED 并给出 CLI 命令，绝不假装成功。
     */
    public String setReplicas(String name, int replicas) {
        if (replicas < 1 || replicas > 7 || replicas % 2 == 0) {
            throw ApiException.badRequest("副本数需为 1/3/5/7，当前 " + replicas);
        }
        AlterTableOptions options;
        try {
            options = new AlterTableOptions();
            Method method = AlterTableOptions.class.getMethod("setNumReplicas", int.class);
            method.invoke(options, replicas);
            options.setWait(true);
        } catch (NoSuchMethodException e) {
            throw ApiException.unsupported("当前 Kudu 客户端（1.17.1）不支持通过 API 修改副本数，"
                    + "请在 master 节点执行：kudu table set_replication_factor --num_replicas="
                    + replicas + " " + name);
        } catch (Exception e) {
            throw ApiException.unsupported("修改副本数不可用：" + e.getMessage());
        }
        try {
            connection.client().alterTable(name, options);
        } catch (Exception e) {
            throw translate(e, "修改副本数失败");
        }
        connection.invalidate(name);
        log.info("table replicas changed: {} -> {}", name, replicas);
        return "API";
    }

    private static boolean exists(KuduClient client, String name) {
        try {
            return client.tableExists(name);
        } catch (Exception e) {
            throw translate(e, "探测表 " + name + " 失败");
        }
    }

    private static void requireConfirm(String name, String confirm) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("表名不能为空");
        }
        if (!name.equals(confirm)) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "CONFIRM_REQUIRED",
                    "危险操作需要确认：请在 confirm 字段原样填入表名 " + name);
        }
    }

    private static ApiException translate(Exception e, String context) {
        ApiException api = KuduErrors.translate(e, context);
        if (api != null) {
            return api;
        }
        return new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL",
                context + "：" + (e.getMessage() == null ? e.toString() : e.getMessage()), e);
    }
}
