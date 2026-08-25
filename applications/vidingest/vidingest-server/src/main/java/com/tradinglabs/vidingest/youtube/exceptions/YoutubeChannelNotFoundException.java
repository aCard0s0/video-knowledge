package com.tradinglabs.vidingest.youtube.exceptions;

import java.util.UUID;

public class YoutubeChannelNotFoundException extends RuntimeException {
    public YoutubeChannelNotFoundException(UUID channelId) {
        super("YouTube channel not found: " + channelId);
    }
}

