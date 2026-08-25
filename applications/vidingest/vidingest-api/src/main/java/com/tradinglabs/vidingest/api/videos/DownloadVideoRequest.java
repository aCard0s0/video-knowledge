package com.tradinglabs.vidingest.api.videos;

import jakarta.validation.constraints.NotBlank;

public record DownloadVideoRequest(
        @NotBlank String url,
        boolean diskOnly,
        boolean progress
) {
}

