package com.tradinglabs.vidingest.client;

import com.tradinglabs.web.client.DownstreamHttpClientException;
import org.springframework.http.HttpStatusCode;

public class VidingestClientException extends DownstreamHttpClientException {

    public VidingestClientException(
            String message,
            HttpStatusCode statusCode,
            String endpoint,
            String responseBody,
            Throwable cause
    ) {
        super(message, statusCode, endpoint, responseBody, cause);
    }
}

