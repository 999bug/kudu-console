package com.kuducrud.kudu;

import com.kuducrud.model.ColumnDef;
import com.kuducrud.web.ApiException;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.ColumnTypeAttributes;
import org.apache.kudu.Type;

/**
 * ColumnSchema ⇄ ColumnDef 映射 + 列定义校验。
 */
public final class SchemaMapper {

    private SchemaMapper() {
    }

    public static ColumnDef toDef(ColumnSchema column) {
        Integer precision = null;
        Integer scale = null;
        Integer length = null;
        ColumnTypeAttributes attrs = column.getTypeAttributes();
        if (attrs != null) {
            if (attrs.hasPrecision()) {
                precision = attrs.getPrecision();
            }
            if (attrs.hasScale()) {
                scale = attrs.getScale();
            }
            if (attrs.hasLength()) {
                length = attrs.getLength();
            }
        }
        int blockSize = column.getDesiredBlockSize();
        return new ColumnDef(
                column.getName(),
                column.getType().name(),
                column.isKey(),
                column.isNullable(),
                precision,
                scale,
                length,
                column.getEncoding() == null ? null : column.getEncoding().name(),
                column.getCompressionAlgorithm() == null ? null : column.getCompressionAlgorithm().name(),
                blockSize > 0 ? blockSize : null,
                KuduValueConverter.formatDefault(column.getDefaultValue()),
                column.getComment()
        );
    }

    public static Type parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("列类型不能为空");
        }
        try {
            return Type.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("不支持的列类型：" + raw);
        }
    }

    public static ColumnSchema toSchema(ColumnDef def) {
        if (def == null || def.name() == null || def.name().isBlank()) {
            throw ApiException.badRequest("列名不能为空");
        }
        String name = def.name().trim();
        Type type = parseType(def.type());
        boolean key = def.key();
        if (key && !KuduValueConverter.canBePrimaryKey(type)) {
            throw ApiException.badRequest("列 " + name + "：Kudu 不允许 " + type + " 作为主键");
        }

        ColumnSchema.ColumnSchemaBuilder builder = new ColumnSchema.ColumnSchemaBuilder(name, type)
                .key(key)
                .nullable(key ? false : def.nullable());

        if (type == Type.DECIMAL) {
            if (def.precision() == null) {
                throw ApiException.badRequest("DECIMAL 列 " + name + " 必须指定 precision");
            }
            int precision = def.precision();
            int scale = def.scale() == null ? 0 : def.scale();
            if (precision < 1 || precision > 38) {
                throw ApiException.badRequest("列 " + name + "：precision 需在 1..38");
            }
            if (scale < 0 || scale > precision) {
                throw ApiException.badRequest("列 " + name + "：scale 需在 0..precision");
            }
            builder.typeAttributes(new ColumnTypeAttributes.ColumnTypeAttributesBuilder()
                    .precision(precision).scale(scale).build());
        } else if (type == Type.VARCHAR) {
            if (def.length() == null) {
                throw ApiException.badRequest("VARCHAR 列 " + name + " 必须指定 length");
            }
            int length = def.length();
            if (length < 1 || length > 65535) {
                throw ApiException.badRequest("列 " + name + "：length 需在 1..65535");
            }
            builder.typeAttributes(new ColumnTypeAttributes.ColumnTypeAttributesBuilder()
                    .length(length).build());
        }

        if (def.encoding() != null && !def.encoding().isBlank()) {
            try {
                builder.encoding(ColumnSchema.Encoding.valueOf(def.encoding().trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("列 " + name + "：未知 encoding " + def.encoding());
            }
        }
        if (def.compression() != null && !def.compression().isBlank()) {
            try {
                builder.compressionAlgorithm(
                        ColumnSchema.CompressionAlgorithm.valueOf(def.compression().trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("列 " + name + "：未知 compression " + def.compression());
            }
        }
        if (def.blockSizeBytes() != null && def.blockSizeBytes() > 0) {
            builder.desiredBlockSize(def.blockSizeBytes());
        }
        if (def.comment() != null && !def.comment().isBlank()) {
            builder.comment(def.comment());
        }
        Object defaultValue = KuduValueConverter.defaultValue(type, def.defaultValue());
        if (defaultValue != null) {
            builder.defaultValue(defaultValue);
        }
        if (!key && !def.nullable() && defaultValue == null) {
            throw ApiException.badRequest("列 " + name + "：非空且非主键的列必须提供默认值");
        }
        return builder.build();
    }
}
