package com.tradinglabs.operation.logging.web;

import java.util.Map;

public interface OperationSummaryExtractor {

    Map<String, Object> summarizeArgs(String[] names, Object[] args);

    Map<String, Object> summarizeBody(Object body);
}

