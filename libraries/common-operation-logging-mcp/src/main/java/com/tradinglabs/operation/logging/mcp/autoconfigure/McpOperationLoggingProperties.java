package com.tradinglabs.operation.logging.mcp.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tradinglabs.mcp.logging")
public class McpOperationLoggingProperties {

    private boolean enabled = false;

    private String eventName = "";

    private boolean includeToolDescription = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEventName() {
        return eventName;
    }

    public void setEventName(String eventName) {
        this.eventName = eventName;
    }

    public boolean isIncludeToolDescription() {
        return includeToolDescription;
    }

    public void setIncludeToolDescription(boolean includeToolDescription) {
        this.includeToolDescription = includeToolDescription;
    }
}

