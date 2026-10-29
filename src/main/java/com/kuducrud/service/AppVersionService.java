package com.kuducrud.service;

import com.kuducrud.model.AppVersion;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 读取应用版本：优先用镜像构建时注入的环境变量，本地 java -jar 回落到 pom.properties。
 * <p>Dockerfile 与 release.yml 会把 APP_VERSION / GIT_COMMIT / BUILD_TIME / APP_IMAGE 写进镜像，
 * 顶栏和 /api/meta/version 因此能直接看出服务器实际在跑哪个版本。
 */
@Service
public class AppVersionService {

    private final Environment environment;
    private volatile AppVersion cached;

    public AppVersionService(Environment environment) {
        this.environment = environment;
    }

    public AppVersion info() {
        AppVersion local = cached;
        if (local == null) {
            synchronized (this) {
                local = cached;
                if (local == null) {
                    local = build();
                    cached = local;
                }
            }
        }
        return local;
    }

    private AppVersion build() {
        String envVersion = value("APP_VERSION");
        String version = envVersion == null ? pomVersion() : envVersion;
        return new AppVersion(
                version,
                value("GIT_COMMIT"),
                value("BUILD_TIME"),
                value("APP_IMAGE"),
                envVersion == null ? "pom" : "env",
                System.getProperty("java.version")
        );
    }

    private String value(String key) {
        String value = environment.getProperty(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String pomVersion() {
        try (InputStream input = AppVersionService.class.getResourceAsStream(
                "/META-INF/maven/com.kuducrud/kudu-console/pom.properties")) {
            if (input == null) {
                return "unknown";
            }
            Properties properties = new Properties();
            properties.load(input);
            String version = properties.getProperty("version");
            return version == null || version.isBlank() ? "unknown" : version.trim();
        } catch (IOException e) {
            return "unknown";
        }
    }
}
