package com.kuducrud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 应用版本信息：镜像构建时由 CI 写入环境变量，本地运行回落到 pom 版本。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppVersion(
        String version,
        String commit,
        String buildTime,
        String image,
        String source,
        String javaVersion
) {
}
