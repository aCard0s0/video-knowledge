package com.tradinglabs.web.client;

import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public final class RestClientErrorSupport {

    private RestClientErrorSupport() {
    }

    public static <E extends DownstreamHttpClientException> E httpError(
            String endpoint,
            RestClientResponseException e,
            DownstreamExceptionFactory<E> exceptionFactory
    ) {
        String body = e.getResponseBodyAsString();
        String message = "HTTP " + e.getStatusCode().value() + " from endpoint " + endpoint;
        return exceptionFactory.create(message, e.getStatusCode(), endpoint, body, e);
    }

    public static <E extends DownstreamHttpClientException> E transportError(
            String endpoint,
            RestClientException e,
            DownstreamExceptionFactory<E> exceptionFactory
    ) {
        return exceptionFactory.create("Request failed for endpoint " + endpoint, null, endpoint, null, e);
    }

    public static <E extends DownstreamHttpClientException> E emptyBody(
            String endpoint,
            DownstreamExceptionFactory<E> exceptionFactory
    ) {
        return exceptionFactory.create("Server returned empty body for endpoint " + endpoint, null, endpoint, null, null);
    }
}

