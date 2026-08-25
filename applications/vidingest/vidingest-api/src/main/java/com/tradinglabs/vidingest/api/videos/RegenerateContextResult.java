package com.tradinglabs.vidingest.api.videos;

import java.util.UUID;

public record RegenerateContextResult(UUID videoId, int chunks) {
}

