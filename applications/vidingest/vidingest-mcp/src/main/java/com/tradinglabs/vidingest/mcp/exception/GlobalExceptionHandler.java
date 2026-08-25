package com.tradinglabs.vidingest.mcp.exception;

import com.tradinglabs.web.McpAwareExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler extends McpAwareExceptionHandler {
}

