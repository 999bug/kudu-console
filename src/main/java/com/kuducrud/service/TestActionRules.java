package com.kuducrud.service;

import com.kuducrud.kudu.TableNameUtil;
import com.kuducrud.model.TestActionSpec;
import com.kuducrud.web.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 备份测试动作与表清单的前置校验。纯逻辑，不碰 Kudu，便于单测与提前报错。
 */
public final class TestActionRules {

    public static final int MAX_TABLES = 50;

    private TestActionRules() {
    }

    public static TestActionSpec normalize(TestActionSpec raw) {
        return raw == null ? new TestActionSpec(false, null, false, null, false, null, null).normalized()
                : raw.normalized();
    }

    public static void validate(TestActionSpec raw) {
        TestActionSpec spec = normalize(raw);
        if (spec.enabledActions() == 0) {
            throw ApiException.badRequest("至少选择一个测试动作");
        }
        if (spec.deleteRows()) {
            checkCount(spec.deleteCount(), "删除");
        }
        if (spec.addRows()) {
            checkCount(spec.addCount(), "添加");
        }
        if (spec.addColumn()) {
            if (!TestActionSpec.COLUMN_TYPES.contains(spec.columnType())) {
                throw ApiException.badRequest("加列类型只支持 " + String.join(" / ", TestActionSpec.COLUMN_TYPES));
            }
            if (!TestActionSpec.COLUMN_MODES.contains(spec.columnMode())) {
                throw ApiException.badRequest("加列模式只支持 REPLACE / ONCE");
            }
        }
    }

    public static List<String> normalizeTables(List<String> tables) {
        if (tables == null || tables.isEmpty()) {
            throw ApiException.badRequest("请至少选择一张表");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String raw : tables) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String table = raw.trim();
            if (TableNameUtil.isSystemTable(table)) {
                throw ApiException.badRequest("不允许操作 Kudu 系统表：" + table);
            }
            unique.add(table);
        }
        if (unique.isEmpty()) {
            throw ApiException.badRequest("请至少选择一张表");
        }
        if (unique.size() > MAX_TABLES) {
            throw ApiException.badRequest("单次最多选择 " + MAX_TABLES + " 张表，当前 " + unique.size() + " 张");
        }
        return new ArrayList<>(unique);
    }

    private static void checkCount(int count, String action) {
        if (count < 1 || count > TestActionSpec.MAX_COUNT) {
            throw ApiException.badRequest(action + "条数需在 1.." + TestActionSpec.MAX_COUNT + "，当前 " + count);
        }
    }
}
