package com.kuducrud.service;

import com.kuducrud.model.AppVersion;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AppVersionServiceTest {

    @Test
    void readsBuildInformationFromEnvironment() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_VERSION", "v1.0.6")
                .withProperty("GIT_COMMIT", "03223f3")
                .withProperty("BUILD_TIME", "2026-10-29T03:20:00Z")
                .withProperty("APP_IMAGE", "ghcr.io/999bug/kudu-console:v1.0.6");

        AppVersion info = new AppVersionService(environment).info();

        assertEquals("v1.0.6", info.version());
        assertEquals("03223f3", info.commit());
        assertEquals("2026-10-29T03:20:00Z", info.buildTime());
        assertEquals("ghcr.io/999bug/kudu-console:v1.0.6", info.image());
        assertEquals("env", info.source());
        assertNotNull(info.javaVersion());
    }

    @Test
    void fallsBackToPomWithoutEnv() {
        AppVersion info = new AppVersionService(new MockEnvironment()).info();

        assertNotNull(info.version());
        assertEquals("pom", info.source());
        assertNull(info.commit());
        assertNull(info.buildTime());
    }
}
