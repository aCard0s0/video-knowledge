package com.tradinglabs.vidingest.api.ocr;

import java.util.List;

/**
 * OCR detections grouped by their parent frame — the shape returned by
 * {@code GET /api/v1/videos/{videoId}/ocr}. Pre-grouping on the server saves agents from
 * having to do it themselves, which is the common case.
 *
 * @param frameId           UUID of the frame as string
 * @param timestampSeconds  frame timestamp in the video timeline
 * @param framePath         absolute path to the JPG on disk (handy for visual QA tools)
 * @param lines             OCR detections from this frame, in PaddleOCR emission order
 */
public record OcrFrameGroup(
        String frameId,
        Double timestampSeconds,
        String framePath,
        List<OcrResultDto> lines
) {
}
