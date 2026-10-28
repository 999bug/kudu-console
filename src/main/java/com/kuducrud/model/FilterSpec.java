package com.kuducrud.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 单个过滤条件。op 取值：
 * EQ / NE / LT / LTE / GT / GTE / IN / IS_NULL / IS_NOT_NULL / PREFIX。
 */
public record FilterSpec(String column, String op, JsonNode value, List<JsonNode> values) {
}
