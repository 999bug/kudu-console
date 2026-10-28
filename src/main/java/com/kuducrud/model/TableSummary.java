package com.kuducrud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 表清单中的一行。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TableSummary(
        String name,
        String shortName,
        String db,
        boolean impalaManaged,
        Integer columnCount,
        Long liveRowCount,
        Long onDiskSize
) {
}
