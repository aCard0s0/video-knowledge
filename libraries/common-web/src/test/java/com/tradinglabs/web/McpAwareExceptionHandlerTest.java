package com.tradinglabs.web;

import com.tradinglabs.mcp.web.McpNoResourceSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class McpAwareExceptionHandlerTest {

    private final McpAwareExceptionHandler handler = new TestExceptionHandler();

    @Test
    void returnsMcpGuidanceForPostToSseEndpoint() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/vidingest/sse");
        request.setContextPath("/vidingest");
        ServletWebRequest webRequest = new ServletWebRequest(request);
        NoResourceFoundException exception = new NoResourceFoundException(
                HttpMethod.POST, request.getRequestURI(), request.getRequestURI());

        var response = handler.handleNoResourceFound(exception, request, webRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo(McpNoResourceSupport.wrongEndpointMessage(request));
        assertThat(response.getBody().path()).isEqualTo(request.getRequestURI());
    }

    @Test
    void returnsStandardPayloadForNormalRoute() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/vidingest/api/v1/videos");
        request.setContextPath("/vidingest");
        ServletWebRequest webRequest = new ServletWebRequest(request);
        NoResourceFoundException exception = new NoResourceFoundException(
                HttpMethod.GET, request.getRequestURI(), request.getRequestURI());

        var response = handler.handleNoResourceFound(exception, request, webRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo(exception.getMessage());
        assertThat(response.getBody().path()).isEqualTo(request.getRequestURI());
    }

    private static final class TestExceptionHandler extends McpAwareExceptionHandler {
    }
}
