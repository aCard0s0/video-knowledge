package com.tradinglabs.operation.logging.web;

import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.CodeSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class ControllerOperationContextAspect {

    private final RestOperationNameResolver operationNameResolver;
    private final OperationSummaryExtractor summaryExtractor;

    public ControllerOperationContextAspect(RestOperationNameResolver operationNameResolver, OperationSummaryExtractor summaryExtractor) {
        this.operationNameResolver = operationNameResolver;
        this.summaryExtractor = summaryExtractor;
    }

    @Around("@within(restController)")
    public Object captureOperationContext(ProceedingJoinPoint joinPoint, RestController restController) throws Throwable {
        HttpServletRequest request = currentRequest().orElse(null);
        if (request == null) {
            return joinPoint.proceed();
        }

        String normalizedPath = HttpOperationLoggingFilter.normalizeUri(request);
        String operation = operationNameResolver.resolve(request, normalizedPath)
                .orElseGet(() -> joinPoint.getSignature().getDeclaringType().getSimpleName() + "." + joinPoint.getSignature().getName());

        request.setAttribute(OperationContextAttributes.OPERATION, operation);

        if (joinPoint.getSignature() instanceof CodeSignature sig) {
            Map<String, Object> input = summaryExtractor.summarizeArgs(sig.getParameterNames(), joinPoint.getArgs());
            if (!input.isEmpty()) {
                request.setAttribute(OperationContextAttributes.INPUT, input);
            }
        }

        Object result = joinPoint.proceed();
        Object body = ControllerResponseUnwrapper.unwrapBody(result);
        if (body instanceof Resource || body instanceof InputStream || body instanceof byte[]) {
            return result;
        }
        Map<String, Object> output = summaryExtractor.summarizeBody(body);
        if (!output.isEmpty()) {
            request.setAttribute(OperationContextAttributes.OUTPUT, output);
        }
        return result;
    }

    private static Optional<HttpServletRequest> currentRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return Optional.empty();
        }
        return Optional.ofNullable(attrs.getRequest());
    }
}

