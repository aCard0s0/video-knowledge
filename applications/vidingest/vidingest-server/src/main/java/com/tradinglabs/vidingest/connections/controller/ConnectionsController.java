package com.tradinglabs.vidingest.connections.controller;

import com.tradinglabs.vidingest.api.connections.ConnectionName;
import com.tradinglabs.vidingest.api.connections.ConnectionSummary;
import com.tradinglabs.vidingest.api.connections.ConnectionTestResult;
import com.tradinglabs.vidingest.api.connections.UpdateConnectionRequest;
import com.tradinglabs.vidingest.api.paths.VidIngestApiPaths;
import com.tradinglabs.vidingest.connections.service.ConnectionProbeService;
import com.tradinglabs.vidingest.connections.service.ConnectionSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Runtime management of the connections to the LLM runtimes and the sidecars.
 *
 * <p>There is no create and no delete-the-thing: the five connections always exist, so this is a
 * fixed collection of settings rather than a CRUD resource. {@code DELETE} means "drop my override
 * and go back to what the environment configured", not "remove the connection".
 *
 * <p>{@code {name}} is bound as the {@link ConnectionName} enum, so an unknown name is rejected by
 * Spring as a type mismatch and rendered as a 400 ProblemDetail by
 * {@code VidingestApiExceptionHandler} — there is no 404 branch because there is no such thing as
 * a connection that does not exist.
 *
 * <p>Timeouts are not exposed here. Each transport consumes them once when its request factory is
 * built, so a runtime edit could not reach the client actually making the calls, and reporting a
 * value the client is not using would be worse than not offering it.
 */
@RestController
@RequestMapping(value = VidIngestApiPaths.CONNECTIONS, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "connections", description = "Base URLs, providers and models for the LLM runtimes and sidecars")
public class ConnectionsController {

    private final ConnectionSettingsService settingsService;
    private final ConnectionProbeService probeService;

    @Operation(operationId = "listConnections",
            summary = "List every connection with its effective settings",
            description = "Values reflect what the running server would use right now, whether that "
                    + "came from the environment or from a stored override. API keys are never returned.")
    @GetMapping
    public List<ConnectionSummary> list() {
        return settingsService.list();
    }

    @Operation(operationId = "getConnection", summary = "Get one connection's effective settings")
    @GetMapping("/{name}")
    public ConnectionSummary get(@PathVariable ConnectionName name) {
        return settingsService.get(name);
    }

    @Operation(operationId = "updateConnection",
            summary = "Override a connection's settings",
            description = "Stores the override and applies it to the running server immediately — no "
                    + "restart. Omit apiKey to keep the stored one, send an empty string to clear it.")
    @PutMapping(value = "/{name}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ConnectionSummary update(@PathVariable ConnectionName name,
                                    @Valid @RequestBody UpdateConnectionRequest request) {
        log.info("REST update connection: name={}", name);
        return settingsService.update(name, request);
    }

    @Operation(operationId = "resetConnection",
            summary = "Drop the override and revert to the configured value",
            description = "Reverts to whatever the environment configured when the server started.")
    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@PathVariable ConnectionName name) {
        log.info("REST reset connection: name={}", name);
        settingsService.reset(name);
    }

    @Operation(operationId = "testConnection",
            summary = "Probe the connection's current settings",
            description = "Always answers 200: an unreachable dependency is a successful answer to "
                    + "\"is it reachable?\", carried as reachable=false with the reason.")
    @PostMapping("/{name}/test")
    @ResponseStatus(HttpStatus.OK)
    public ConnectionTestResult test(@PathVariable ConnectionName name) {
        return probeService.probe(name);
    }
}
