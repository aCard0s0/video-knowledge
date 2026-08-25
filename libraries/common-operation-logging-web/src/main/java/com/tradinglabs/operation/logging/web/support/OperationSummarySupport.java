package com.tradinglabs.operation.logging.web.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class OperationSummarySupport {

    private OperationSummarySupport() {
    }

    public static Object normalizeScalar(Object value) {
        if (value instanceof UUID) return value;
        if (value instanceof String s) return s.length() <= 200 ? s : s.substring(0, 200) + "... (truncated)";
        if (value instanceof Integer || value instanceof Long || value instanceof Boolean) return value;
        if (value instanceof BigDecimal) return value;
        if (value instanceof Enum<?>) return value.toString();
        if (value instanceof Instant) return value.toString();
        return null;
    }

    public static int listSize(Object v) {
        if (v instanceof List<?> l) return l.size();
        return v == null ? 0 : 1;
    }

    public static void pick(Map<String, Object> out, Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v != null) {
            out.put(key, v);
        }
    }

    public static Map<String, Object> mapOfNonNull(Object... keyValues) {
        if (keyValues == null || keyValues.length == 0) {
            return Map.of();
        }
        if (keyValues.length % 2 != 0) {
            return Map.of("error", "invalid mapOfNonNull args", "argsCount", keyValues.length);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            Object k = keyValues[i];
            Object v = keyValues[i + 1];
            if (!(k instanceof String key)) {
                continue;
            }
            if (v == null) {
                continue;
            }
            out.put(key, v);
        }
        if (out.isEmpty()) {
            return Map.of();
        }
        // Keep insertion order for readability in structured logs.
        out.values().removeIf(Objects::isNull);
        return out;
    }
}

