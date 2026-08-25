package com.tradinglabs.vidingest.core.diarization.service;

import java.util.UUID;

/**
 * No {@code vidingest_speakers} row for the given id. Mirrors {@code VideoNotFoundException}
 * so the diarization feature raises the same kind of domain exception as the rest of the app
 * rather than a Spring {@code ResponseStatusException} thrown from the web layer.
 */
public class SpeakerNotFoundException extends RuntimeException {

    public SpeakerNotFoundException(UUID speakerId) {
        super("Speaker not found: " + speakerId);
    }
}
