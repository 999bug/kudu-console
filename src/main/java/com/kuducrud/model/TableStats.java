package com.kuducrud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 表统计（来自 master /metrics 或 client.getTableStatistics），均为估算值。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TableStats(Long liveRowCount, Long onDiskSize, String source) {
}
