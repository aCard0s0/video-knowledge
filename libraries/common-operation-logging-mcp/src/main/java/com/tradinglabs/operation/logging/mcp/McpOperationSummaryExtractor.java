package com.tradinglabs.operation.logging.mcp;

import java.util.Map;

public interface McpOperationSummaryExtractor {

    Map<String, Object> summarizeArgs(String[] names, Object[] args);

    Map<String, Object> summarizeBody(Object body);
}

