package com.tradinglabs.vidingest.api.ocr;

import java.util.List;

/**
 * Public shape of a {@code vidingest_ocr_results} row.
 *
 * @param id           UUID as string
 * @param frameId      parent frame UUID
 * @param text         the detected text line
 * @param confidence   PaddleOCR confidence in {@code [0,1]} — may be null on edge cases
 * @param bbox         polygon corners as {@code [[x,y], ...]} — may be null when the
 *                     sidecar didn't provide a well-formed polygon
 * @param language     ISO 639-1 code, nullable
 * @param createdAt    ISO-8601 timestamp
 */
public record OcrResultDto(
        String id,
        String frameId,
        String text,
        Float confidence,
        List<List<Double>> bbox,
        String language,
        String createdAt
) {
}
