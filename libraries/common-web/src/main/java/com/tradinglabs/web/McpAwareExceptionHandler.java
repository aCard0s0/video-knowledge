package com.tradinglabs.web;

import com.tradinglabs.mcp.web.McpNoResourceSupport;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Exception handler for apps that serve MCP over SSE.
 * Detects the common client mistake of POSTing JSON-RPC messages to the SSE
 * discovery endpoint instead of the message endpoint.
 */
@Slf4j
public abstract class McpAwareExceptionHandler extends BaseGlobalExceptionHandler {

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResourceFound(
            NoResourceFoundException ex,
            HttpServletRequest httpRequest,
            WebRequest webRequest) {

        String method = httpRequest.getMethod();
        String path = httpRequest.getRequestURI();

        if (McpNoResourceSupport.isMcpSsePost(httpRequest)) {
            log.warn("MCP request sent to SSE discovery endpoint (correlationId={}). method={}, path={}, expectedMessageEndpoint={}",
                    CorrelationIds.current(), method, path, McpNoResourceSupport.expectedMessageEndpoint(httpRequest));
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiErrorResponse.of(
                            HttpStatus.NOT_FOUND.value(), "Not Found",
                            McpNoResourceSupport.wrongEndpointMessage(httpRequest), path));
        }

        log.warn("No resource found (correlationId={}). method={}, path={}", CorrelationIds.current(), method, path);
        return respond(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), webRequest);
    }
}
