package com.tradinglabs.operation.logging.web.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.operation.logging.web.OperationSummaryExtractor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DefaultOperationSummaryExtractor implements OperationSummaryExtractor {

    private final ObjectMapper objectMapper;

    public DefaultOperationSummaryExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, Object> summarizeArgs(String[] names, Object[] args) {
        if (names == null || args == null || names.length != args.length) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            Object value = args[i];
            if (value == null || name == null || name.isBlank()) {
                continue;
            }
            Object normalized = OperationSummarySupport.normalizeScalar(value);
            if (normalized != null) {
                out.put(name, normalized);
                continue;
            }
            if (value instanceof Map<?, ?> m) {
                out.put(name, Map.of("size", m.size()));
                continue;
            }
            if (value instanceof List<?> l) {
                out.put(name + "Count", l.size());
                continue;
            }
            if (value instanceof Iterable<?> it) {
                int count = 0;
                for (Object ignored : it) {
                    count++;
                }
                out.put(name + "Count", count);
                continue;
            }
            out.put(name, Map.of("type", value.getClass().getSimpleName()));
        }
        return out;
    }

    @Override
    public Map<String, Object> summarizeBody(Object body) {
        if (body == null) {
            return Map.of();
        }
        if (body instanceof Map<?, ?> m) {
            return Map.of("size", m.size());
        }
        if (body instanceof List<?> l) {
            return Map.of("itemsCount", l.size());
        }
        Object normalized = OperationSummarySupport.normalizeScalar(body);
        if (normalized != null) {
            return Map.of("value", normalized);
        }
        try {
            Map<?, ?> asMap = objectMapper.convertValue(body, Map.class);
            if (asMap != null && !asMap.isEmpty()) {
                return Map.of("keys", asMap.keySet().stream().limit(30).toList(), "size", asMap.size());
            }
        } catch (Exception ignore) {
            // fall through
        }
        return Map.of("type", body.getClass().getName());
    }
}

