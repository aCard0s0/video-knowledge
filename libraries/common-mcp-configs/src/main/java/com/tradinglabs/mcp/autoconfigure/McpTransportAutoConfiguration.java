package com.tradinglabs.mcp.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@AutoConfiguration
@ConditionalOnClass(WebMvcSseServerTransportProvider.class)
@EnableConfigurationProperties(McpServerTransportProperties.class)
public class McpTransportAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper mcpObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(WebMvcSseServerTransportProvider.class)
    WebMvcSseServerTransportProvider webMvcSseServerTransportProvider(
            ObjectMapper objectMapper,
            McpServerTransportProperties properties,
            @Value("${server.servlet.context-path:}") String contextPath
    ) {
        String configuredBaseUrl = properties.baseUrl();
        String effectiveBaseUrl = configuredBaseUrl == null || configuredBaseUrl.isBlank()
                ? contextPath
                : configuredBaseUrl;

        return WebMvcSseServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .baseUrl(effectiveBaseUrl)
                .sseEndpoint(properties.sseEndpoint())
                .messageEndpoint(properties.sseMessageEndpoint())
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true")
    @ConditionalOnBean(WebMvcSseServerTransportProvider.class)
    @ConditionalOnMissingBean(name = "mvcMcpRouterFunction")
    RouterFunction<ServerResponse> mvcMcpRouterFunction(
            WebMvcSseServerTransportProvider transportProvider
    ) {
        return transportProvider.getRouterFunction();
    }
}
