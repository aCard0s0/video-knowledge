package com.tradinglabs.vidingest.api.videos;

public record DownloadToDiskResult(
        String videoPath,
        String metadataPath
) {
}

