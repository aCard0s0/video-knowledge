package com.tradinglabs.vidingest.videos.exceptions;

import java.util.UUID;

public class VideoNotFoundException extends RuntimeException {

    public VideoNotFoundException(UUID videoId) {
        super("Video not found: " + videoId);
    }
}

