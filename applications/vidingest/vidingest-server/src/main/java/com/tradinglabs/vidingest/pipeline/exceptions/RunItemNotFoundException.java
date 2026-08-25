package com.tradinglabs.vidingest.pipeline.exceptions;

import java.util.UUID;

public class RunItemNotFoundException extends RuntimeException {

    public RunItemNotFoundException(UUID runId, UUID itemId) {
        super("Pipeline run item not found: " + itemId + " (run " + runId + ")");
    }
}

