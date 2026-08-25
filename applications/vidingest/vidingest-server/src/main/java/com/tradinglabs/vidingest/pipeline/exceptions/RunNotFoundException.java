package com.tradinglabs.vidingest.pipeline.exceptions;

import java.util.UUID;

public class RunNotFoundException extends RuntimeException {

    public RunNotFoundException(UUID runId) {
        super("Pipeline run not found: " + runId);
    }
}

