package com.tradinglabs.operation.logging.web;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

@FunctionalInterface
public interface RestOperationNameResolver {

    Optional<String> resolve(HttpServletRequest request, String normalizedPath);
}

