package com.tradinglabs.operation.logging.mcp;

import com.tradinglabs.common.logging.operation.OperationLogMarkers;
import com.tradinglabs.common.logging.operation.OperationLogSanitizer;
import com.tradinglabs.operation.logging.mcp.autoconfigure.McpOperationLoggingProperties;
import net.logstash.logback.marker.LogstashMarker;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.CodeSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Map;

import static net.logstash.logback.marker.Markers.append;

@Aspect
public class McpToolOperationLoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(McpToolOperationLoggingAspect.class);

    private final McpOperationLoggingProperties props;
    private final McpOperationSummaryExtractor summaryExtractor;
    private final String defaultEventName;

    public McpToolOperationLoggingAspect(
            McpOperationLoggingProperties props,
            McpOperationSummaryExtractor summaryExtractor,
            String defaultEventName
    ) {
        this.props = props;
        this.summaryExtractor = summaryExtractor;
        this.defaultEventName = (defaultEventName == null || defaultEventName.isBlank())
                ? "tradinglabs.operation"
                : defaultEventName.trim();
    }

    @Around("@annotation(org.springaicommunity.mcp.annotation.McpTool)")
    public Object logToolInvocation(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTimeMs = System.currentTimeMillis();

        Map<String, Object> input = Map.of();
        if (joinPoint.getSignature() instanceof CodeSignature sig) {
            input = summaryExtractor.summarizeArgs(sig.getParameterNames(), joinPoint.getArgs());
        }

        String operation = "mcp." + joinPoint.getSignature().getDeclaringType().getSimpleName()
                + "." + joinPoint.getSignature().getName();

        try {
            Object result = joinPoint.proceed();
            long durationMs = System.currentTimeMillis() - startTimeMs;
            Map<String, Object> output = summaryExtractor.summarizeBody(result);

            LogstashMarker marker = OperationLogMarkers.mcpOperationCompleted(
                    resolveEventName(),
                    operation,
                    durationMs,
                    OperationLogSanitizer.sanitizeMap(input),
                    OperationLogSanitizer.sanitizeMap(output),
                    false,
                    null
            );

            if (props.isIncludeToolDescription()) {
                String desc = resolveToolDescription(joinPoint);
                if (desc != null && !desc.isBlank()) {
                    marker = marker.and(append("toolDescription", desc));
                }
            }

            log.info(marker, "MCP tool invocation completed");
            return result;
        } catch (Throwable t) {
            long durationMs = System.currentTimeMillis() - startTimeMs;
            LogstashMarker marker = OperationLogMarkers.mcpOperationCompleted(
                    resolveEventName(),
                    operation,
                    durationMs,
                    OperationLogSanitizer.sanitizeMap(input),
                    Map.of(),
                    true,
                    t
            );

            if (props.isIncludeToolDescription()) {
                String desc = resolveToolDescription(joinPoint);
                if (desc != null && !desc.isBlank()) {
                    marker = marker.and(append("toolDescription", desc));
                }
            }

            log.error(marker, "MCP tool invocation failed");
            throw t;
        }
    }

    private String resolveEventName() {
        String configured = props.getEventName();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return defaultEventName;
    }

    private static String resolveToolDescription(ProceedingJoinPoint joinPoint) {
        try {
            Class<?> annotationType = Class.forName("org.springaicommunity.mcp.annotation.McpTool");
            if (!annotationType.isAnnotation()) {
                return null;
            }

            Method method = resolveMethod(joinPoint);
            if (method == null) {
                return null;
            }

            Object annotation = method.getAnnotation((Class) annotationType);
            if (annotation == null) {
                return null;
            }

            Method descriptionMethod = annotationType.getMethod("description");
            Object value = descriptionMethod.invoke(annotation);
            return value == null ? null : value.toString();
        } catch (Exception ignore) {
            return null;
        }
    }

    private static Method resolveMethod(ProceedingJoinPoint joinPoint) {
        try {
            Class<?> declaring = joinPoint.getSignature().getDeclaringType();
            String name = joinPoint.getSignature().getName();
            if (joinPoint.getSignature() instanceof CodeSignature sig) {
                Class<?>[] paramTypes = sig.getParameterTypes();
                return declaring.getMethod(name, paramTypes);
            }
            for (Method m : declaring.getMethods()) {
                if (m.getName().equals(name)) {
                    return m;
                }
            }
            return null;
        } catch (Exception ignore) {
            return null;
        }
    }
}

