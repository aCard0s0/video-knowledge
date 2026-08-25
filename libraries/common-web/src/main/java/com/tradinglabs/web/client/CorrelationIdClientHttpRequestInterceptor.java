package com.tradinglabs.web.client;

import com.tradinglabs.web.CorrelationIds;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.UUID;

/**
 * Propagates {@link CorrelationIds#HEADER} and keeps MDC consistent for the duration of the downstream call.
 *
 * <p>If neither MDC nor the outgoing request already contains a correlation id, one is generated.</p>
 */
public final class CorrelationIdClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String previous = MDC.get(CorrelationIds.MDC_KEY);

        String headerCorrelationId = request.getHeaders().getFirst(CorrelationIds.HEADER);
        String correlationId = StringUtils.hasText(headerCorrelationId) ? headerCorrelationId : CorrelationIds.current();
        if (!StringUtils.hasText(correlationId)) {
            correlationId = UUID.randomUUID().toString();
        }

        if (!StringUtils.hasText(headerCorrelationId)) {
            request.getHeaders().set(CorrelationIds.HEADER, correlationId);
        }

        MDC.put(CorrelationIds.MDC_KEY, correlationId);
        try {
            return execution.execute(request, body);
        } finally {
            if (previous == null) {
                MDC.remove(CorrelationIds.MDC_KEY);
            } else {
                MDC.put(CorrelationIds.MDC_KEY, previous);
            }
        }
    }
}

