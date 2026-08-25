package com.tradinglabs.mcp.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "spring.ai.mcp.server")
public record McpServerTransportProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String baseUrl,
        @DefaultValue("/sse") String sseEndpoint,
        @DefaultValue("/mcp/message") String sseMessageEndpoint
) {
}
