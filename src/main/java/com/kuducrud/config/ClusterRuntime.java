package com.kuducrud.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kuducrud.job.JobRegistry;
import com.kuducrud.job.TruncateService;
import com.kuducrud.kudu.KuduConnectionManager;
import com.kuducrud.kudu.KuduSessionTemplate;
import com.kuducrud.model.ClusterConfig;
import com.kuducrud.model.ClusterStatus;
import com.kuducrud.service.ClusterStatusService;
import com.kuducrud.service.DdlService;
import com.kuducrud.service.RowService;
import com.kuducrud.service.TableCatalogService;
import com.kuducrud.service.TableSchemaService;
import com.kuducrud.service.webui.MasterWebUiClient;

/**
 * 一个平台的运行时：连接、Web UI 客户端和全部服务实例。
 * <p>每个平台一份，互不影响（连接、表缓存、统计缓存都是独立的）。
 */
public class ClusterRuntime {

    private final ClusterConfig config;
    private final KuduConnectionManager connection;
    private final MasterWebUiClient webUi;
    private final TableCatalogService catalog;
    private final TableSchemaService schema;
    private final RowService rows;
    private final DdlService ddl;
    private final ClusterStatusService status;
    private final TruncateService truncate;

    public ClusterRuntime(ClusterConfig config, KuduProperties props, ObjectMapper mapper, JobRegistry jobs) {
        this.config = config;
        int operationTimeout = config.operationTimeout(props.getOperationTimeoutMs());
        int adminTimeout = config.adminTimeout(props.getAdminTimeoutMs());
        this.connection = new KuduConnectionManager(config.masters(), operationTimeout, adminTimeout,
                props.getConnectTimeoutMs());
        this.webUi = new MasterWebUiClient(config, connection, props, mapper);
        this.catalog = new TableCatalogService(connection, webUi);
        this.schema = new TableSchemaService(connection, catalog, props);
        this.rows = new RowService(connection, new KuduSessionTemplate(connection, props), props);
        this.ddl = new DdlService(connection);
        this.status = new ClusterStatusService(connection, webUi, config);
        this.truncate = new TruncateService(connection, jobs, props, config.id());
    }

    public ClusterConfig config() {
        return config;
    }

    public String id() {
        return config.id();
    }

    public KuduConnectionManager connection() {
        return connection;
    }

    public MasterWebUiClient webUi() {
        return webUi;
    }

    public TableCatalogService catalog() {
        return catalog;
    }

    public TableSchemaService schema() {
        return schema;
    }

    public RowService rows() {
        return rows;
    }

    public DdlService ddl() {
        return ddl;
    }

    public ClusterStatusService status() {
        return status;
    }

    public TruncateService truncate() {
        return truncate;
    }

    public ClusterStatus statusSnapshot() {
        return status.status();
    }

    public void close() {
        connection.shutdown();
    }
}
