package com.tradinglabs.vidingest.api.videos;

public record DownloadVideoResponse(
        VideoSummary video,
        DownloadToDiskResult downloadToDisk
) {
}

