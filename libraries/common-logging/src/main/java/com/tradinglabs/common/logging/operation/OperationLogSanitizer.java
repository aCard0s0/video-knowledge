package com.tradinglabs.common.logging.operation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Best-effort sanitization for structured logging payloads.
 *
 * <p>Designed to avoid leaking obvious secrets and to cap payload sizes.
 */
public final class OperationLogSanitizer {

    private static final String MASK = "***MASKED***";
    private static final int DEFAULT_MAX_STRING_LENGTH = 500;
    private static final int DEFAULT_MAX_COLLECTION_ITEMS = 50;
    private static final int DEFAULT_MAX_DEPTH = 4;

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password",
            "token",
            "secret",
            "apikey",
            "api_key",
            "authorization",
            "cookie",
            "set-cookie"
    );

    private OperationLogSanitizer() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static Map<String, Object> sanitizeMap(Map<String, ?> in) {
        return sanitizeMap(in, DEFAULT_MAX_DEPTH);
    }

    public static Map<String, Object> sanitizeMap(Map<String, ?> in, int maxDepth) {
        if (in == null || in.isEmpty()) {
            return Map.of();
        }
        return sanitizeMapInternal(in, maxDepth);
    }

    private static Map<String, Object> sanitizeMapInternal(Map<String, ?> in, int remainingDepth) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : in.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (isSensitiveKey(key)) {
                out.put(key, MASK);
                continue;
            }
            out.put(key, sanitizeValue(value, remainingDepth - 1));
        }
        return out;
    }

    private static Object sanitizeValue(Object value, int remainingDepth) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence cs) {
            return truncate(cs.toString(), DEFAULT_MAX_STRING_LENGTH);
        }
        if (remainingDepth <= 0) {
            return truncate(value.toString(), DEFAULT_MAX_STRING_LENGTH);
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object k = e.getKey();
                String key = k == null ? "null" : k.toString();
                if (isSensitiveKey(key)) {
                    out.put(key, MASK);
                    continue;
                }
                out.put(key, sanitizeValue(e.getValue(), remainingDepth - 1));
            }
            return out;
        }
        if (value instanceof Iterable<?> it) {
            List<Object> out = new ArrayList<>();
            int i = 0;
            for (Object item : it) {
                if (i++ >= DEFAULT_MAX_COLLECTION_ITEMS) {
                    out.add("... (truncated)");
                    break;
                }
                out.add(sanitizeValue(item, remainingDepth - 1));
            }
            return out;
        }
        if (value.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(value);
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < Math.min(len, DEFAULT_MAX_COLLECTION_ITEMS); i++) {
                out.add(sanitizeValue(java.lang.reflect.Array.get(value, i), remainingDepth - 1));
            }
            if (len > DEFAULT_MAX_COLLECTION_ITEMS) {
                out.add("... (truncated)");
            }
            return out;
        }
        return value;
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        return SENSITIVE_KEYS.contains(normalized);
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "... (truncated, totalLength=" + s.length() + ")";
    }
}

