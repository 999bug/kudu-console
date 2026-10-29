package com.kuducrud.job;

import com.kuducrud.kudu.KuduConnectionManager;
import com.kuducrud.kudu.TablePrefixMatcher;
import com.kuducrud.model.JobView;
import com.kuducrud.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 按表名前缀删表：把匹配前缀的所有表逐个删除。
 * <p>和删库的区别是匹配范围更自由（任意前缀，不要求是完整的 db 段），所以删除前先走
 * {@link #preview(String)} 让调用方确认命中了哪些表；真正的删除走异步任务，可看进度、可取消。
 */
public class DropPrefixService {

    private static final Logger log = LoggerFactory.getLogger(DropPrefixService.class);

    private final KuduConnectionManager connection;
    private final JobRegistry registry;
    private final String clusterId;

    public DropPrefixService(KuduConnectionManager connection, JobRegistry registry, String clusterId) {
        this.connection = connection;
        this.registry = registry;
        this.clusterId = clusterId;
    }

    /** 删除前的预览：本次会删哪些表，以及有多少系统表被跳过。 */
    public record PrefixPreview(String prefix, int count, int systemSkipped, List<String> tables) {
    }

    public PrefixPreview preview(String prefix) {
        String normalized = requirePrefix(prefix);
        List<String> names = connection.listTables();
        List<String> tables = TablePrefixMatcher.select(names, normalized);
        return new PrefixPreview(normalized, tables.size(),
                TablePrefixMatcher.systemMatched(names, normalized).size(), tables);
    }

    public JobView submit(String prefix, String confirm, Boolean acknowledge) {
        String normalized = requirePrefix(prefix);
        if (!normalized.equals(confirm)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONFIRM_REQUIRED",
                    "危险操作需要确认：请在 confirm 字段原样填入前缀 " + normalized);
        }
        if (!Boolean.TRUE.equals(acknowledge)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONFIRM_REQUIRED",
                    "请确认已知晓匹配到的表都会被删除（acknowledge=true）");
        }

        List<String> tables = TablePrefixMatcher.select(connection.listTables(), normalized);
        if (tables.isEmpty()) {
            throw ApiException.notFound("没有以 " + normalized + " 开头的表（系统表不参与删除）");
        }
        String conflict = DropConflicts.forPrefix(registry, clusterId, normalized, tables);
        if (conflict != null) {
            throw ApiException.conflict("JOB_CONFLICT", conflict);
        }

        JobHandle handle = registry.create("DROP_PREFIX", clusterId, normalized,
                JobRegistry.prefixKey(clusterId, normalized), "前缀 " + normalized);
        handle.totalEstimate().set(tables.size());
        registry.submit(handle, () -> TableBatchDropper.run(connection, handle, tables, "按前缀删表"));
        log.info("drop-prefix job submitted: prefix={}, tables={}", normalized, tables.size());
        return handle.toView();
    }

    private static String requirePrefix(String prefix) {
        String normalized = TablePrefixMatcher.normalize(prefix);
        if (normalized.isEmpty()) {
            throw ApiException.badRequest("前缀不能为空");
        }
        return normalized;
    }
}
