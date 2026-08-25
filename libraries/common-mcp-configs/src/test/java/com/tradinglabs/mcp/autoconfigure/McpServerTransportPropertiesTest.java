package com.tradinglabs.mcp.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class McpServerTransportPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(McpTransportAutoConfiguration.class))
            .withUserConfiguration(TestSupportConfig.class);

    @Test
    void usesDefaultPropertyValues() {
        contextRunner.run(context -> {
            McpServerTransportProperties properties = context.getBean(McpServerTransportProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.baseUrl()).isEqualTo("");
            assertThat(properties.sseEndpoint()).isEqualTo("/sse");
            assertThat(properties.sseMessageEndpoint()).isEqualTo("/mcp/message");
        });
    }

    @Test
    void bindsExplicitPropertyValues() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.mcp.server.enabled=true",
                        "spring.ai.mcp.server.base-url=/vidingest",
                        "spring.ai.mcp.server.sse-endpoint=/sse-custom",
                        "spring.ai.mcp.server.sse-message-endpoint=/mcp/custom-message"
                )
                .run(context -> {
                    McpServerTransportProperties properties = context.getBean(McpServerTransportProperties.class);
                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.baseUrl()).isEqualTo("/vidingest");
                    assertThat(properties.sseEndpoint()).isEqualTo("/sse-custom");
                    assertThat(properties.sseMessageEndpoint()).isEqualTo("/mcp/custom-message");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestSupportConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
