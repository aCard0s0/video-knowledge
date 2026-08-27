package com.tradinglabs.vidingest.health;

import com.tradinglabs.vidingest.api.health.LlmStatus;
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
    private final LlmStatusService llmStatusService;

    @GetMapping("/ready")
    @Operation(operationId = "readiness", summary = "Readiness probe", description = "Returns 200 when DB and storage paths are usable; otherwise 503 with details.")
    public ResponseEntity<ReadinessResult> readiness() {
        ReadinessResult result = readinessService.checkReadiness();
        HttpStatus status = result.ready() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(result);
    }

    // Sub-path literals rather than VidIngestApiPaths.HEALTH_* — those constants are absolute
    // and the class-level @RequestMapping already contributes HEALTH.
    @GetMapping("/llm")
    @Operation(operationId = "llmStatus", summary = "LLM runtime status",
            description = "Probes the configured model runtime (Ollama, LM Studio, llama.cpp, mlx, vLLM, ...) "
                    + "for reachability and its installed/loaded models.")
    public LlmStatus llmStatus() {
        return llmStatusService.probe();
    }
}

