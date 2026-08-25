package com.tradinglabs.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.tradinglabs.web.client.DownstreamHttpClientException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Base exception handler providing common error responses for REST APIs.
 * Apps extend this class (annotated with @RestControllerAdvice) and add
 * domain-specific handlers as needed.
 */
@Slf4j
public abstract class BaseGlobalExceptionHandler {

    @ExceptionHandler({
            HandlerMethodValidationException.class,
            BindException.class,
            ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class,
            ConversionFailedException.class
    })
    public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception ex, WebRequest request) {
        Map<String, String> errors = extractValidationErrors(ex);
        if (errors.isEmpty()) {
            log.warn("Bad request (correlationId={}): {}", CorrelationIds.current(), ex.getMessage());
            return respond(HttpStatus.BAD_REQUEST, "Bad Request", "Request validation failed", request);
        }
        log.warn("Bad request validation failed (correlationId={}): {}", CorrelationIds.current(), errors);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.withValidation(
                        HttpStatus.BAD_REQUEST.value(),
                        "Validation Failed",
                        "Request validation failed",
                        extractPath(request),
                        errors
                ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex, WebRequest request) {
        log.warn("Illegal argument (correlationId={}): {}", CorrelationIds.current(), ex.getMessage());
        return respond(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalState(IllegalStateException ex, WebRequest request) {
        log.warn("Illegal state (correlationId={}): {}", CorrelationIds.current(), ex.getMessage());
        return respond(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex, WebRequest request) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }
        log.warn("Validation failed (correlationId={}): {}", CorrelationIds.current(), errors);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.withValidation(
                        HttpStatus.BAD_REQUEST.value(), "Validation Failed",
                        "Request validation failed", extractPath(request), errors));
    }

    @ExceptionHandler(DownstreamHttpClientException.class)
    public ResponseEntity<ApiErrorResponse> handleDownstreamHttpClientException(
            DownstreamHttpClientException ex,
            WebRequest request
    ) {
        HttpStatus status = ex.getStatusCode() == null
                ? HttpStatus.BAD_GATEWAY
                : HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.BAD_GATEWAY;
        }

        log.warn("Downstream HTTP call failed (correlationId={}). status={}, endpoint={}, message={}",
                CorrelationIds.current(),
                status.value(),
                ex.getEndpoint(),
                ex.getMessage());
        return respond(status, status.getReasonPhrase(), ex.getMessage(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            WebRequest request
    ) {
        Set<HttpMethod> supportedMethods = ex.getSupportedHttpMethods();
        String message = "Method not allowed";
        if (supportedMethods != null && !supportedMethods.isEmpty()) {
            String supportedList = supportedMethods.stream()
                    .map(method -> method.name())
                    .sorted()
                    .collect(Collectors.joining(", "));
            message = "Method not allowed; supported methods: " + supportedList;
        }

        log.warn("Method not allowed (correlationId={}). method={}, supported={}",
                CorrelationIds.current(),
                ex.getMethod(),
                supportedMethods);

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (supportedMethods != null && !supportedMethods.isEmpty()) {
            builder.allow(supportedMethods.toArray(HttpMethod[]::new));
        }
        return builder.body(ApiErrorResponse.of(
                HttpStatus.METHOD_NOT_ALLOWED.value(),
                HttpStatus.METHOD_NOT_ALLOWED.getReasonPhrase(),
                message,
                extractPath(request)
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(Exception ex, WebRequest request) {
        log.error("Unhandled exception (correlationId={})", CorrelationIds.current(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "An unexpected error occurred", request);
    }

    protected ResponseEntity<ApiErrorResponse> respond(HttpStatus status, String error,
                                                        String message, WebRequest request) {
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(status.value(), error, message, extractPath(request)));
    }

    protected String extractPath(WebRequest request) {
        return request.getDescription(false).replace("uri=", "");
    }

    private static Map<String, String> extractValidationErrors(Exception ex) {
        if (ex instanceof BindException bind) {
            Map<String, String> errors = new HashMap<>();
            for (FieldError error : bind.getBindingResult().getFieldErrors()) {
                errors.put(error.getField(), error.getDefaultMessage());
            }
            return errors;
        }
        if (ex instanceof HandlerMethodValidationException methodValidation) {
            Map<String, String> errors = new HashMap<>();
            methodValidation.getAllErrors().forEach(error -> {
                if (error instanceof FieldError fieldError) {
                    errors.put(fieldError.getField(), fieldError.getDefaultMessage());
                } else if (error instanceof ObjectError objectError) {
                    errors.put(objectError.getObjectName(), objectError.getDefaultMessage());
                }
            });
            return errors;
        }
        if (ex instanceof ConstraintViolationException constraintViolation) {
            Map<String, String> errors = new HashMap<>();
            for (ConstraintViolation<?> violation : constraintViolation.getConstraintViolations()) {
                String key = violation.getPropertyPath() == null ? "constraint" : violation.getPropertyPath().toString();
                errors.put(key, violation.getMessage());
            }
            return errors;
        }
        return Map.of();
    }
}
