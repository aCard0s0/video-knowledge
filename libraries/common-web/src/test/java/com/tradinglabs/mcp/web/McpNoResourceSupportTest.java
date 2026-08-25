package com.tradinglabs.mcp.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class McpNoResourceSupportTest {

    @Test
    void identifiesPostToSseEndpoint() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/vidingest/sse");
        request.setContextPath("/vidingest");

        assertThat(McpNoResourceSupport.isMcpSsePost(request)).isTrue();
    }

    @Test
    void expectedMessageEndpointUsesContextPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/vidingest/sse");
        request.setContextPath("/vidingest");

        assertThat(McpNoResourceSupport.expectedMessageEndpoint(request))
                .isEqualTo("/vidingest/mcp/message?sessionId=...");
    }

    @Test
    void wrongEndpointMessageFallsBackToUriWhenContextPathMissing() {
        HttpServletRequest request = new MockHttpServletRequest("POST", "/vidingest/sse");

        assertThat(McpNoResourceSupport.wrongEndpointMessage(request))
                .isEqualTo("MCP messages must be POSTed to /vidingest/mcp/message?sessionId=... after GET /vidingest/sse");
    }
}
