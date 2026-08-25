package com.tradinglabs.operation.logging.web;

public final class OperationContextAttributes {

    public static final String OPERATION = "tradinglabs.operation";
    public static final String INPUT = "tradinglabs.operation.input";
    public static final String OUTPUT = "tradinglabs.operation.output";

    private OperationContextAttributes() {
        throw new UnsupportedOperationException("Utility class");
    }
}

