package com.kuducrud.service;

import com.kuducrud.kudu.SchemaMapper;
import com.kuducrud.kudu.TableNameUtil;
import com.kuducrud.model.ColumnDef;
import com.kuducrud.model.CreateTableRequest;
import com.kuducrud.model.PartitionDef;
import com.kuducrud.web.ApiException;
import org.apache.kudu.ColumnSchema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 建表/加列的前置校验：把能提前发现的问题挡在发请求之前，字段级错误直接返回前端。
 */
public final class DdlValidator {

    private DdlValidator() {
    }

    public static void validateTableName(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("表名不能为空");
        }
        if (name.length() > 256) {
            throw ApiException.badRequest("表名长度不能超过 256");
        }
        if (name.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.badRequest("表名不能包含控制字符");
        }
        if (name.startsWith(".") || name.endsWith(".")) {
            throw ApiException.badRequest("表名不能以点开头或结尾");
        }
        if (TableNameUtil.isSystemTable(name)) {
            throw ApiException.badRequest("不允许操作系统表（" + name + "）");
        }
    }

    public static void validateCreate(CreateTableRequest request) {
        if (request == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        validateTableName(request.name());

        int replicas = request.numReplicas() == null ? 1 : request.numReplicas();
        if (replicas < 1 || replicas > 7 || replicas % 2 == 0) {
            throw ApiException.badRequest("副本数需为 1/3/5/7，当前 " + replicas);
        }

        List<ColumnDef> columns = request.columns();
        if (columns == null || columns.isEmpty()) {
            throw ApiException.badRequest("至少需要一个列");
        }
        Set<String> seen = new HashSet<>();
        List<String> primaryKey = new ArrayList<>();
        boolean keyPhase = true;
        for (ColumnDef def : columns) {
            if (def.name() == null || def.name().isBlank()) {
                throw ApiException.badRequest("列名不能为空");
            }
            String name = def.name().trim();
            if (!seen.add(name)) {
                throw ApiException.badRequest("列名重复：" + name);
            }
            if (def.key()) {
                if (!keyPhase) {
                    throw ApiException.badRequest("主键列必须连续排在最前面，列 " + name + " 出现在非主键列之后");
                }
                primaryKey.add(name);
            } else {
                keyPhase = false;
            }
            // 逐列构造一次，提前暴露类型/属性错误
            SchemaMapper.toSchema(def);
        }
        if (primaryKey.isEmpty()) {
            throw ApiException.badRequest("至少需要一个主键列");
        }

        validatePartition(request, primaryKey);
    }

    private static void validatePartition(CreateTableRequest request, List<String> primaryKey) {
        Set<String> hashColumns = new LinkedHashSet<>();
        if (request.hashPartitions() != null) {
            for (PartitionDef.HashPartition hash : request.hashPartitions()) {
                if (hash == null || hash.columns() == null || hash.columns().isEmpty()) {
                    continue;
                }
                if (hash.buckets() < 1) {
                    throw ApiException.badRequest("hash 分区 buckets 必须 ≥ 1");
                }
                for (String column : hash.columns()) {
                    if (!primaryKey.contains(column)) {
                        throw ApiException.badRequest("hash 分区列 " + column + " 不是主键列");
                    }
                    if (!hashColumns.add(column)) {
                        throw ApiException.badRequest("hash 分区列 " + column + " 重复出现");
                    }
                }
            }
        }

        List<String> rangeColumns = request.rangePartitionColumns();
        if (rangeColumns != null && !rangeColumns.isEmpty()) {
            // Kudu 要求 range 分区列是主键前缀
            for (int i = 0; i < rangeColumns.size(); i++) {
                if (i >= primaryKey.size() || !primaryKey.get(i).equals(rangeColumns.get(i))) {
                    throw ApiException.badRequest("range 分区列必须是主键的前缀，期望 "
                            + primaryKey.subList(0, Math.min(rangeColumns.size(), primaryKey.size())));
                }
            }
            for (String column : rangeColumns) {
                if (hashColumns.contains(column)) {
                    throw ApiException.badRequest("range 分区列 " + column + " 不能同时用于 hash 分区");
                }
            }
        } else if (request.rangePartitions() != null && !request.rangePartitions().isEmpty()) {
            throw ApiException.badRequest("设置了 range 分区边界但没有指定 range 分区列");
        }
    }

    public static void validateAddColumn(List<String> existingNames, ColumnDef def) {
        if (def == null) {
            throw ApiException.badRequest("列定义不能为空");
        }
        String name = def.name() == null ? "" : def.name().trim();
        if (existingNames.contains(name)) {
            throw ApiException.badRequest("列已存在：" + name);
        }
        if (!def.nullable() && (def.defaultValue() == null || def.defaultValue().isBlank())) {
            throw ApiException.badRequest("非空列必须提供默认值");
        }
        ColumnSchema schema = SchemaMapper.toSchema(new ColumnDef(name, def.type(), false, def.nullable(),
                def.precision(), def.scale(), def.length(), def.encoding(), def.compression(),
                def.blockSizeBytes(), def.defaultValue(), def.comment()));
        if (schema.getType() == null) {
            throw ApiException.badRequest("列类型不能为空");
        }
    }
}
