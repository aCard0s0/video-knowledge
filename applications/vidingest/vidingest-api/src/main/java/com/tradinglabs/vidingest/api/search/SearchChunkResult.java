package com.tradinglabs.vidingest.api.search;

public record SearchChunkResult(
        String chunkId,
        String videoId,
        Integer chunkIndex,
        String snippet,
        String videoTitle,
        String channelName,
        String filePath
) {
}

