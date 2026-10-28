package com.kuducrud.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 控制台配置。除 masters/超时外，均带默认值，可用 application.yml 或命令行覆盖。
 */
@ConfigurationProperties(prefix = "kudu-console")
public class KuduProperties {

    /** master RPC 地址，逗号分隔，形如 host1:7051,host2:7051 */
    private String masters = "127.0.0.1:7051";
    private int operationTimeoutMs = 30_000;
    private int adminTimeoutMs = 60_000;
    private int connectTimeoutMs = 10_000;

    private WebUi webUi = new WebUi();
    private Query query = new Query();
    private Write write = new Write();
    private Truncate truncate = new Truncate();

    public String getMasters() {
        return masters;
    }

    public void setMasters(String masters) {
        this.masters = masters;
    }

    public int getOperationTimeoutMs() {
        return operationTimeoutMs;
    }

    public void setOperationTimeoutMs(int operationTimeoutMs) {
        this.operationTimeoutMs = operationTimeoutMs;
    }

    public int getAdminTimeoutMs() {
        return adminTimeoutMs;
    }

    public void setAdminTimeoutMs(int adminTimeoutMs) {
        this.adminTimeoutMs = adminTimeoutMs;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public WebUi getWebUi() {
        return webUi;
    }

    public void setWebUi(WebUi webUi) {
        this.webUi = webUi;
    }

    public Query getQuery() {
        return query;
    }

    public void setQuery(Query query) {
        this.query = query;
    }

    public Write getWrite() {
        return write;
    }

    public void setWrite(Write write) {
        this.write = write;
    }

    public Truncate getTruncate() {
        return truncate;
    }

    public void setTruncate(Truncate truncate) {
        this.truncate = truncate;
    }

    public static class WebUi {
        private boolean enabled = true;
        private int portOffset = 1000;
        private String scheme = "http";
        private List<String> baseUrls = new ArrayList<>();
        private int connectTimeoutMs = 2000;
        private int readTimeoutMs = 5000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPortOffset() {
            return portOffset;
        }

        public void setPortOffset(int portOffset) {
            this.portOffset = portOffset;
        }

        public String getScheme() {
            return scheme;
        }

        public void setScheme(String scheme) {
            this.scheme = scheme;
        }

        public List<String> getBaseUrls() {
            return baseUrls;
        }

        public void setBaseUrls(List<String> baseUrls) {
            this.baseUrls = baseUrls;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }
    }

    public static class Query {
        private int defaultLimit = 100;
        private int maxLimit = 500;
        private int maxOffset = 10_000;
        private int maxScanRows = 50_000;
        private int batchBytes = 1_048_576;

        public int getDefaultLimit() {
            return defaultLimit;
        }

        public void setDefaultLimit(int defaultLimit) {
            this.defaultLimit = defaultLimit;
        }

        public int getMaxLimit() {
            return maxLimit;
        }

        public void setMaxLimit(int maxLimit) {
            this.maxLimit = maxLimit;
        }

        public int getMaxOffset() {
            return maxOffset;
        }

        public void setMaxOffset(int maxOffset) {
            this.maxOffset = maxOffset;
        }

        public int getMaxScanRows() {
            return maxScanRows;
        }

        public void setMaxScanRows(int maxScanRows) {
            this.maxScanRows = maxScanRows;
        }

        public int getBatchBytes() {
            return batchBytes;
        }

        public void setBatchBytes(int batchBytes) {
            this.batchBytes = batchBytes;
        }
    }

    public static class Write {
        private int batchSize = 1000;

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }

    public static class Truncate {
        private int batchSize = 1000;
        private int maxRows = 2_000_000;

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxRows() {
            return maxRows;
        }

        public void setMaxRows(int maxRows) {
            this.maxRows = maxRows;
        }
    }
}
