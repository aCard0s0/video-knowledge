package com.tradinglabs.vidingest.api.search;

import java.util.UUID;

/** One semantic-search hit over the context chunks. */
public record SearchChunkResult(
        UUID chunkId,
        UUID videoId,
        Integer chunkIndex,
        String snippet,
        String videoTitle,
        String channelName,
        String filePath
) {
}
