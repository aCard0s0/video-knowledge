package com.tradinglabs.vidingest.core.transcription.service;

import com.tradinglabs.vidingest.commons.PhaseFailureException;

public class TranscriptionFailureException extends PhaseFailureException {

    public TranscriptionFailureException(String message) {
        super(message);
    }

    public TranscriptionFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}

