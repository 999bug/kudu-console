package com.kuducrud.job;

import com.kuducrud.kudu.KuduConnectionManager;
import com.kuducrud.kudu.TableNameUtil;
import com.kuducrud.model.JobView;
import com.kuducrud.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 删除「库」（Kudu 没有真正的 catalog，库只是表名前缀）：把该前缀下的所有表逐个删除。
 * <p>表多时耗时长，所以走异步任务，可看进度、可取消；单个表删除走同步接口即可。
 */
public class DropDbService {

    private static final Logger log = LoggerFactory.getLogger(DropDbService.class);

    private final KuduConnectionManager connection;
    private final JobRegistry registry;
    private final String clusterId;

    public DropDbService(KuduConnectionManager connection, JobRegistry registry, String clusterId) {
        this.connection = connection;
        this.registry = registry;
        this.clusterId = clusterId;
    }

    public JobView submit(String db, String confirm, Boolean acknowledge) {
        String name = db == null ? "" : db.trim();
        if (name.isEmpty()) {
            throw ApiException.badRequest("库名不能为空");
        }
        if (!name.equals(confirm)) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "CONFIRM_REQUIRED",
                    "危险操作需要确认：请在 confirm 字段原样填入库名 " + name);
        }
        if (!Boolean.TRUE.equals(acknowledge)) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "CONFIRM_REQUIRED",
                    "请确认已知晓该库下所有表都会被删除（acknowledge=true）");
        }
        if ("sys".equalsIgnoreCase(name) || "kudu".equalsIgnoreCase(name)) {
            throw ApiException.badRequest("不允许删除系统库：" + name);
        }

        List<String> tables = tablesOf(name);
        if (tables.isEmpty()) {
            throw ApiException.notFound("库 " + name + " 下没有表");
        }
        String conflict = DropConflicts.forDb(registry, clusterId, name);
        if (conflict != null) {
            throw ApiException.conflict("JOB_CONFLICT", conflict);
        }

        JobHandle handle = registry.create("DROP_DB", clusterId, name,
                JobRegistry.dbKey(clusterId, name), "库 " + name);
        handle.totalEstimate().set(tables.size());
        registry.submit(handle, () -> TableBatchDropper.run(connection, handle, tables, "删库"));
        log.info("drop-db job submitted: db={}, tables={}", name, tables.size());
        return handle.toView();
    }

    /** 该前缀下的所有表；Impala 托管的 impala::db.table 也归入同一库。 */
    public List<String> tablesOf(String db) {
        List<String> result = new ArrayList<>();
        for (String table : connection.listTables()) {
            if (db.equalsIgnoreCase(TableNameUtil.dbOf(table))) {
                result.add(table);
            }
        }
        return result;
    }
}
