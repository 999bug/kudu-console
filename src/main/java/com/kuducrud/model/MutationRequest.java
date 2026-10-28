package com.kuducrud.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 行写入请求。op 取值：INSERT / UPSERT / UPDATE / DELETE。
 * <p>DELETE 只需要主键列；UPDATE 只更新提交的非主键列。
 */
public record MutationRequest(
        String op,
        List<Map<String, JsonNode>> rows,
        Boolean stopOnError
) {
}
