package com.tradinglabs.operation.logging.mcp.support;

import com.tradinglabs.operation.logging.mcp.McpOperationSummaryExtractor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DefaultMcpOperationSummaryExtractor implements McpOperationSummaryExtractor {

    @Override
    public Map<String, Object> summarizeArgs(String[] names, Object[] args) {
        if (names == null || args == null || names.length != args.length) {
            return Map.of();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            Object value = args[i];
            if (name == null || name.isBlank() || value == null) {
                continue;
            }
            if (value instanceof Map<?, ?> m) {
                out.put(name + "Size", m.size());
                continue;
            }
            if (value instanceof List<?> l) {
                out.put(name + "Count", l.size());
                continue;
            }
            Object normalized = normalizeScalar(value);
            if (normalized != null) {
                out.put(name, normalized);
            }
        }

        return out;
    }

    @Override
    public Map<String, Object> summarizeBody(Object body) {
        if (body == null) {
            return Map.of();
        }
        if (body instanceof Map<?, ?> m) {
            return Map.of("type", "map", "size", m.size());
        }
        if (body instanceof List<?> l) {
            return Map.of("type", "list", "itemsCount", l.size());
        }

        Object normalized = normalizeScalar(body);
        if (normalized != null) {
            return Map.of("value", normalized);
        }
        return Map.of("type", body.getClass().getName());
    }

    private static Object normalizeScalar(Object value) {
        if (value instanceof UUID) return value;
        if (value instanceof String s) return s.length() <= 200 ? s : s.substring(0, 200) + "... (truncated)";
        if (value instanceof Integer || value instanceof Long || value instanceof Boolean) return value;
        if (value instanceof BigDecimal) return value;
        if (value instanceof Enum<?>) return value.toString();
        if (value instanceof Instant) return value.toString();
        return null;
    }
}

