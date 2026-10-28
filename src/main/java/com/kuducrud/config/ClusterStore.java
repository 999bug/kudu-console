package com.kuducrud.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kuducrud.model.ClusterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 平台清单的持久化：config/clusters.json。
 * <p>首次启动时依次尝试：clusters.json → 旧的 connection.json（单平台迁移）→ application.yml 的 masters。
 */
@Component
public class ClusterStore {

    private static final Logger log = LoggerFactory.getLogger(ClusterStore.class);

    private final ObjectMapper mapper;
    private final Path file = Path.of("config", "clusters.json");
    private final Path legacy = Path.of("config", "connection.json");

    public ClusterStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record State(String activeId, List<ClusterConfig> clusters) {
    }

    /** 旧的单平台配置，仅用于迁移。 */
    private record Legacy(String masters, Integer operationTimeoutMs, Integer adminTimeoutMs,
                          Boolean webUiEnabled, Integer webUiPortOffset, String webUiScheme) {
    }

    public State load(ClusterConfig seed) {
        State saved = readState();
        if (saved != null && !saved.clusters().isEmpty()) {
            String active = saved.activeId();
            boolean exists = saved.clusters().stream().anyMatch(c -> c.id().equals(active));
            return new State(exists ? active : saved.clusters().get(0).id(), saved.clusters());
        }
        ClusterConfig migrated = migrateLegacy();
        if (migrated != null) {
            log.info("migrated legacy connection.json to platform '{}'", migrated.name());
            State state = new State(migrated.id(), List.of(migrated));
            save(state);
            return state;
        }
        State state = new State(seed.id(), List.of(seed));
        save(state);
        return state;
    }

    private State readState() {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            State state = mapper.readValue(file.toFile(), State.class);
            if (state == null || state.clusters() == null) {
                return null;
            }
            List<ClusterConfig> valid = state.clusters().stream()
                    .filter(c -> c != null && c.id() != null && c.masters() != null && !c.masters().isBlank())
                    .toList();
            if (valid.isEmpty()) {
                return null;
            }
            return new State(state.activeId(), valid);
        } catch (Exception e) {
            log.warn("read {} failed: {}", file, e.getMessage());
            return null;
        }
    }

    private ClusterConfig migrateLegacy() {
        if (!Files.isRegularFile(legacy)) {
            return null;
        }
        try {
            Legacy old = mapper.readValue(legacy.toFile(), Legacy.class);
            if (old == null || old.masters() == null || old.masters().isBlank()) {
                return null;
            }
            return new ClusterConfig("default", "默认集群", old.masters(), old.operationTimeoutMs(),
                    old.adminTimeoutMs(), old.webUiEnabled(), old.webUiPortOffset());
        } catch (Exception e) {
            log.warn("read legacy {} failed: {}", legacy, e.getMessage());
            return null;
        }
    }

    public void save(State state) {
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), state);
        } catch (Exception e) {
            log.warn("save {} failed: {}", file, e.getMessage());
        }
    }
}
