package com.tradinglabs.mcp.web;

import jakarta.servlet.http.HttpServletRequest;

public final class McpNoResourceSupport {

    private McpNoResourceSupport() {
    }

    public static boolean isMcpSsePost(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI() != null
                && request.getRequestURI().endsWith("/sse");
    }

    public static String expectedMessageEndpoint(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        if (contextPath == null || contextPath.isBlank()) {
            contextPath = contextPathFromUri(request.getRequestURI());
        }
        return contextPath + "/mcp/message?sessionId=...";
    }

    public static String wrongEndpointMessage(HttpServletRequest request) {
        String messageEndpoint = expectedMessageEndpoint(request);
        String sseEndpoint = messageEndpoint.replace("/mcp/message?sessionId=...", "/sse");
        return "MCP messages must be POSTed to " + messageEndpoint + " after GET " + sseEndpoint;
    }

    private static String contextPathFromUri(String requestUri) {
        if (requestUri == null || requestUri.isBlank()) {
            return "";
        }
        int sseIndex = requestUri.lastIndexOf("/sse");
        if (sseIndex > 0) {
            return requestUri.substring(0, sseIndex);
        }
        return "";
    }
}
