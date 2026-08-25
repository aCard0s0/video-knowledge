package com.tradinglabs.vidingest.core.transcription.service;

public class TranscriptionFailureException extends RuntimeException {

    public TranscriptionFailureException(String message) {
        super(message);
    }

    public TranscriptionFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}

