package com.tradinglabs.operation.logging.web.support;

import com.tradinglabs.operation.logging.web.RestOperationNameResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Optional;

public class BestMatchingPatternRestOperationNameResolver implements RestOperationNameResolver {

    @Override
    public Optional<String> resolve(HttpServletRequest request, String normalizedPath) {
        if (request == null) {
            return Optional.empty();
        }
        Object attr = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (attr instanceof String pattern && !pattern.isBlank()) {
            String method = request.getMethod();
            if (method == null || method.isBlank()) {
                return Optional.of(pattern.trim());
            }
            return Optional.of(method + " " + pattern.trim());
        }
        return Optional.empty();
    }
}

