package com.tradinglabs.vidingest.health;

import com.tradinglabs.vidingest.api.health.OllamaStatus;
import com.tradinglabs.vidingest.api.health.ReadinessResult;
import com.tradinglabs.vidingest.api.paths.VidIngestApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = VidIngestApiPaths.HEALTH, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "health", description = "Health and readiness APIs")
public class HealthController {

    private final ReadinessService readinessService;
    private final OllamaStatusService ollamaStatusService;

    @GetMapping("/ready")
    @Operation(summary = "Readiness probe", description = "Returns 200 when DB and storage paths are usable; otherwise 503 with details.")
    public ResponseEntity<?> readiness() {
        ReadinessResult result = readinessService.checkReadiness();
        HttpStatus status = result.ready() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(result);
    }

    @GetMapping("/ollama")
    @Operation(summary = "Ollama status", description = "Probes the configured Ollama server for reachability and running/installed models.")
    public OllamaStatus ollama() {
        return ollamaStatusService.probe();
    }
}

