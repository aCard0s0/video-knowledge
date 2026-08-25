package com.tradinglabs.operation.logging.web;

import org.springframework.http.ResponseEntity;

public final class ControllerResponseUnwrapper {

    private ControllerResponseUnwrapper() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static Object unwrapBody(Object controllerReturnValue) {
        if (controllerReturnValue instanceof ResponseEntity<?> entity) {
            return entity.getBody();
        }
        return controllerReturnValue;
    }
}

