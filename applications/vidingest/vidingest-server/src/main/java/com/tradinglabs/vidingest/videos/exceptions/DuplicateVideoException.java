package com.tradinglabs.vidingest.videos.exceptions;

/**
 * Thrown when a video already exists for a given (source, sourceVideoId) pair.
 */
public class DuplicateVideoException extends RuntimeException {

    private final String source;
    private final String sourceVideoId;

    public DuplicateVideoException(String source, String sourceVideoId) {
        super(message(source, sourceVideoId));
        this.source = source;
        this.sourceVideoId = sourceVideoId;
    }

    public DuplicateVideoException(String source, String sourceVideoId, Throwable cause) {
        super(message(source, sourceVideoId), cause);
        this.source = source;
        this.sourceVideoId = sourceVideoId;
    }

    public String source() {
        return source;
    }

    public String sourceVideoId() {
        return sourceVideoId;
    }

    private static String message(String source, String sourceVideoId) {
        return "Video already ingested: " + source + "/" + sourceVideoId;
    }
}

