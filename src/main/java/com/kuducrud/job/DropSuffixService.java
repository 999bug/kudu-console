package com.kuducrud.job;

import com.kuducrud.kudu.KuduConnectionManager;
import com.kuducrud.kudu.TableSuffixMatcher;
import com.kuducrud.model.JobView;
import com.kuducrud.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 按表名后缀删表：把匹配后缀的所有表逐个删除。
 * <p>典型用途是清理同一批备份/临时表（如 {@code *_bak_20240101}）。后缀命中面跨库、比前缀更广，
 * 所以删除前先走 {@link #preview(String)} 让调用方确认命中了哪些表；真正的删除走异步任务，可看进度、可取消。
 */
public class DropSuffixService {

    private static final Logger log = LoggerFactory.getLogger(DropSuffixService.class);

    private final KuduConnectionManager connection;
    private final JobRegistry registry;
    private final String clusterId;

    public DropSuffixService(KuduConnectionManager connection, JobRegistry registry, String clusterId) {
        this.connection = connection;
        this.registry = registry;
        this.clusterId = clusterId;
    }

    /** 删除前的预览：本次会删哪些表，以及有多少系统表被跳过。 */
    public record SuffixPreview(String suffix, int count, int systemSkipped, List<String> tables) {
    }

    public SuffixPreview preview(String suffix) {
        String normalized = requireSuffix(suffix);
        List<String> names = connection.listTables();
        List<String> tables = TableSuffixMatcher.select(names, normalized);
        return new SuffixPreview(normalized, tables.size(),
                TableSuffixMatcher.systemMatched(names, normalized).size(), tables);
    }

    public JobView submit(String suffix, String confirm, Boolean acknowledge) {
        String normalized = requireSuffix(suffix);
        if (!normalized.equals(confirm)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONFIRM_REQUIRED",
                    "危险操作需要确认：请在 confirm 字段原样填入后缀 " + normalized);
        }
        if (!Boolean.TRUE.equals(acknowledge)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONFIRM_REQUIRED",
                    "请确认已知晓匹配到的表都会被删除（acknowledge=true）");
        }

        List<String> tables = TableSuffixMatcher.select(connection.listTables(), normalized);
        if (tables.isEmpty()) {
            throw ApiException.notFound("没有以 " + normalized + " 结尾的表（系统表不参与删除）");
        }
        String conflict = DropConflicts.forSuffix(registry, clusterId, normalized, tables);
        if (conflict != null) {
            throw ApiException.conflict("JOB_CONFLICT", conflict);
        }

        JobHandle handle = registry.create("DROP_SUFFIX", clusterId, normalized,
                JobRegistry.suffixKey(clusterId, normalized), "后缀 " + normalized);
        handle.totalEstimate().set(tables.size());
        registry.submit(handle, () -> TableBatchDropper.run(connection, handle, tables, "按后缀删表"));
        log.info("drop-suffix job submitted: suffix={}, tables={}", normalized, tables.size());
        return handle.toView();
    }

    private static String requireSuffix(String suffix) {
        String normalized = TableSuffixMatcher.normalize(suffix);
        if (normalized.isEmpty()) {
            throw ApiException.badRequest("后缀不能为空");
        }
        return normalized;
    }
}
