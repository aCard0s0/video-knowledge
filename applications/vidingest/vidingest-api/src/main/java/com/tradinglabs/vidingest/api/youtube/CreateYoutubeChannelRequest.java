package com.tradinglabs.vidingest.api.youtube;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateYoutubeChannelRequest(
        @NotBlank
        @Size(max = 2000)
        String url,
        @Size(max = 255)
        String displayName
) {
}

