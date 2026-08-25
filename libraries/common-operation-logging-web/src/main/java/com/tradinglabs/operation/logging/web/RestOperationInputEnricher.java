package com.tradinglabs.operation.logging.web;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;

@FunctionalInterface
public interface RestOperationInputEnricher {

    Map<String, Object> enrich(HttpServletRequest request, Map<String, Object> input);
}

