package com.kuducrud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 备份测试动作：删除数据 / 修改表结构 / 添加数据，三者可同时生效。
 * <p>字段为 null 时按默认值处理（见 {@link #normalized()}）；校验在服务层做。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TestActionSpec(
        Boolean deleteRows,
        Integer deleteCount,
        Boolean addRows,
        Integer addCount,
        Boolean addColumn,
        String columnType,
        String columnMode
) {

    public static final int DEFAULT_COUNT = 5;
    public static final int MAX_COUNT = 100;
    public static final String DEFAULT_COLUMN_TYPE = "STRING";
    public static final String MODE_REPLACE = "REPLACE";
    public static final String MODE_ONCE = "ONCE";
    public static final List<String> COLUMN_TYPES = List.of("STRING", "INT32", "INT64", "DOUBLE", "BOOL");
    public static final List<String> COLUMN_MODES = List.of(MODE_REPLACE, MODE_ONCE);

    public TestActionSpec normalized() {
        return new TestActionSpec(
                Boolean.TRUE.equals(deleteRows),
                countOr(deleteCount),
                Boolean.TRUE.equals(addRows),
                countOr(addCount),
                Boolean.TRUE.equals(addColumn),
                textOr(columnType, DEFAULT_COLUMN_TYPE),
                textOr(columnMode, MODE_REPLACE)
        );
    }

    public int enabledActions() {
        int count = 0;
        if (Boolean.TRUE.equals(deleteRows)) {
            count++;
        }
        if (Boolean.TRUE.equals(addRows)) {
            count++;
        }
        if (Boolean.TRUE.equals(addColumn)) {
            count++;
        }
        return count;
    }

    private static int countOr(Integer value) {
        return value == null ? DEFAULT_COUNT : value;
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase();
    }
}
