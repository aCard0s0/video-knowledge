package com.tradinglabs.vidingest.videos.exceptions;

public class LocalStorageException extends RuntimeException {

    public LocalStorageException(String message) {
        super(message);
    }

    public LocalStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}

