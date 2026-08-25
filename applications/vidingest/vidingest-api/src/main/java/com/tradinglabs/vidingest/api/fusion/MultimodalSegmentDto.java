package com.tradinglabs.vidingest.api.fusion;

import java.util.List;

/**
 * Public shape of a {@code vidingest_multimodal_segments} row — the unified per-window
 * view of transcript + speakers + OCR that downstream consumers (M6 KNOWLEDGE, M7
 * CONTEXT) read. Exposed via {@code GET /api/v1/videos/{videoId}/multimodal-timeline}.
 *
 * @param id              UUID as string
 * @param videoId         UUID of the parent video
 * @param segmentIndex    dense 0-based index along the video timeline
 * @param startSeconds    inclusive window start
 * @param endSeconds      exclusive window end
 * @param transcriptText  concatenated transcript segments overlapping this window — nullable
 * @param ocrText         deduplicated OCR text from frames in this window — nullable
 * @param speakerIds      distinct speaker UUIDs from the transcript segments — may be empty
 * @param createdAt       ISO-8601 timestamp
 */
public record MultimodalSegmentDto(
        String id,
        String videoId,
        int segmentIndex,
        double startSeconds,
        double endSeconds,
        String transcriptText,
        String ocrText,
        List<String> speakerIds,
        String createdAt
) {
}
