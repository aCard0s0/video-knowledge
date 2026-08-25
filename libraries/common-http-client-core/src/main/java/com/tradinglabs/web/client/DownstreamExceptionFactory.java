package com.tradinglabs.web.client;

import org.springframework.http.HttpStatusCode;

@FunctionalInterface
public interface DownstreamExceptionFactory<E extends DownstreamHttpClientException> {

    E create(String message, HttpStatusCode statusCode, String endpoint, String responseBody, Throwable cause);
}

