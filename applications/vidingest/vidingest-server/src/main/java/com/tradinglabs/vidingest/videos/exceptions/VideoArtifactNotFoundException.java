package com.tradinglabs.vidingest.videos.exceptions;

import java.util.UUID;

public class VideoArtifactNotFoundException extends RuntimeException {

    public VideoArtifactNotFoundException(UUID videoId, String artifact) {
        super("Video artifact not found: " + artifact + " (videoId=" + videoId + ")");
    }
}

