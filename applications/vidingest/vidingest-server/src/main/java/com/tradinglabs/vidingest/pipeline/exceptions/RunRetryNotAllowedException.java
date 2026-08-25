package com.tradinglabs.vidingest.pipeline.exceptions;

public class RunRetryNotAllowedException extends RuntimeException {

    public RunRetryNotAllowedException(String message) {
        super(message);
    }
}

