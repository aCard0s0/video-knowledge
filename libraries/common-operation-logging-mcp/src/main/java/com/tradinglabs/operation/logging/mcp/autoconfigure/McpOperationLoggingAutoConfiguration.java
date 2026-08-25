package com.tradinglabs.operation.logging.mcp.autoconfigure;

import com.tradinglabs.operation.logging.mcp.McpOperationSummaryExtractor;
import com.tradinglabs.operation.logging.mcp.McpToolOperationLoggingAspect;
import com.tradinglabs.operation.logging.mcp.support.DefaultMcpOperationSummaryExtractor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration
@ConditionalOnClass(name = "org.springaicommunity.mcp.annotation.McpTool")
@EnableConfigurationProperties(McpOperationLoggingProperties.class)
public class McpOperationLoggingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    McpOperationSummaryExtractor mcpOperationSummaryExtractor() {
        return new DefaultMcpOperationSummaryExtractor();
    }

    @Bean
    @ConditionalOnProperty(prefix = "tradinglabs.mcp.logging", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    McpToolOperationLoggingAspect mcpToolOperationLoggingAspect(
            McpOperationLoggingProperties properties,
            McpOperationSummaryExtractor summaryExtractor,
            Environment environment
    ) {
        String appName = environment.getProperty("spring.application.name", "");
        String defaultEventName = (appName != null && !appName.isBlank())
                ? appName.trim() + ".operation"
                : "tradinglabs.operation";
        return new McpToolOperationLoggingAspect(properties, summaryExtractor, defaultEventName);
    }
}

