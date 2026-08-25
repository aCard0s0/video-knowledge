package com.tradinglabs.web.client;

import org.springframework.http.HttpStatusCode;

public class DownstreamHttpClientException extends RuntimeException {

    private final HttpStatusCode statusCode;
    private final String endpoint;
    private final String responseBody;

    public DownstreamHttpClientException(
            String message,
            HttpStatusCode statusCode,
            String endpoint,
            String responseBody,
            Throwable cause
    ) {
        super(message, cause);
        this.statusCode = statusCode;
        this.endpoint = endpoint;
        this.responseBody = responseBody;
    }

    public HttpStatusCode getStatusCode() {
        return statusCode;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public String getRootCauseMessage() {
        Throwable cause = getCause();
        while (cause != null && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause == null ? null : cause.getMessage();
    }
}

