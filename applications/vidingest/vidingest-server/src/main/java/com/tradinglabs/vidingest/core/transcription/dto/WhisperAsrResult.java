package com.tradinglabs.vidingest.core.transcription.dto;

import java.util.List;

public record WhisperAsrResult(
        String rawJson,
        String text,
        String language,
        List<WhisperSegment> segments
) {
}
