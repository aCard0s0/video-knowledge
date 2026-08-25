package com.tradinglabs.vidingest.health;

import com.tradinglabs.vidingest.api.health.ReadinessResult;
import com.tradinglabs.vidingest.config.ProjectPathResolver;
import com.tradinglabs.vidingest.config.VideoStorageConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReadinessService {

    private final DataSource dataSource;
    private final VideoStorageConfig storageConfig;
    private final ProjectPathResolver pathResolver;

    public ReadinessResult checkReadiness() {
        Map<String, String> checks = new LinkedHashMap<>();
        boolean ok = true;

        try (Connection conn = dataSource.getConnection()) {
            try (var stmt = conn.prepareStatement("SELECT 1")) {
                stmt.execute();
            }
            checks.put("db", "ok");
        } catch (Exception e) {
            ok = false;
            checks.put("db", "error: " + e.getMessage());
        }

        String videoPath = (storageConfig.getVideoPath() != null && !storageConfig.getVideoPath().isBlank())
                ? storageConfig.getVideoPath()
                : pathResolver.getVideoPath();

        ok &= checkDirectory("videoPath", videoPath, checks);

        return new ReadinessResult(ok, checks);
    }

    private boolean checkDirectory(String key, String pathStr, Map<String, String> checks) {
        try {
            Path path = Paths.get(pathStr);
            Files.createDirectories(path);
            boolean writable = Files.isDirectory(path) && Files.isWritable(path);
            if (writable) {
                checks.put(key, "ok: " + path);
                return true;
            }
            checks.put(key, "not-writable: " + path);
            return false;
        } catch (Exception e) {
            log.warn("Readiness check failed for {}={}", key, pathStr, e);
            checks.put(key, "error: " + e.getMessage());
            return false;
        }
    }
}

