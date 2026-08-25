package com.tradinglabs.vidingest.pipeline.util;

import java.util.Optional;

public final class VideoUrlValidator {

    private VideoUrlValidator() {
    }

    public static Optional<String> validate(String url) {
        if (url == null || url.isBlank()) {
            return Optional.of("url must not be blank");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return Optional.of("url must start with http:// or https://");
        }
        return Optional.empty();
    }
}
