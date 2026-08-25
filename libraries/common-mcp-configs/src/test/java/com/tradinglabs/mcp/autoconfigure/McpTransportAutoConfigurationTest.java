package com.tradinglabs.mcp.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class McpTransportAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(McpTransportAutoConfiguration.class))
            .withUserConfiguration(TestSupportConfig.class);

    @Test
    void createsTransportAndRouterBeansWhenEnabled() {
        contextRunner
                .withPropertyValues("spring.ai.mcp.server.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(McpServerTransportProperties.class);
                    assertThat(context).hasSingleBean(WebMvcSseServerTransportProvider.class);
                    assertThat(context).hasSingleBean(RouterFunction.class);
                });
    }

    @Test
    void doesNotCreateTransportBeansWhenDisabled() {
        contextRunner
                .withPropertyValues("spring.ai.mcp.server.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(McpServerTransportProperties.class);
                    assertThat(context).doesNotHaveBean(WebMvcSseServerTransportProvider.class);
                    assertThat(context).doesNotHaveBean(RouterFunction.class);
                });
    }

    @Test
    void bindsCustomProperties() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.mcp.server.enabled=true",
                        "spring.ai.mcp.server.base-url=/demo",
                        "spring.ai.mcp.server.sse-endpoint=/events",
                        "spring.ai.mcp.server.sse-message-endpoint=/messages"
                )
                .run(context -> {
                    McpServerTransportProperties properties = context.getBean(McpServerTransportProperties.class);
                    assertThat(properties.baseUrl()).isEqualTo("/demo");
                    assertThat(properties.sseEndpoint()).isEqualTo("/events");
                    assertThat(properties.sseMessageEndpoint()).isEqualTo("/messages");
                });
    }

    @Test
    void usesUserProvidedTransportProvider() {
        contextRunner
                .withPropertyValues("spring.ai.mcp.server.enabled=true")
                .withUserConfiguration(CustomTransportConfig.class)
                .run(context -> {
                    WebMvcSseServerTransportProvider provided =
                            context.getBean("customTransportProvider", WebMvcSseServerTransportProvider.class);
                    assertThat(context.getBean(WebMvcSseServerTransportProvider.class)).isSameAs(provided);
                    assertThat(context).hasSingleBean(RouterFunction.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestSupportConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomTransportConfig {

        @Bean("customTransportProvider")
        WebMvcSseServerTransportProvider customTransportProvider(ObjectMapper objectMapper) {
            return WebMvcSseServerTransportProvider.builder()
                    .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                    .baseUrl("/custom")
                    .sseEndpoint("/custom-sse")
                    .messageEndpoint("/custom-message")
                    .build();
        }
    }
}
