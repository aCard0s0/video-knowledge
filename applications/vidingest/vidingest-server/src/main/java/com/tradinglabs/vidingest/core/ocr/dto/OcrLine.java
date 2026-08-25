package com.tradinglabs.vidingest.core.ocr.dto;

import java.util.List;

/**
 * One text detection from PaddleOCR for a single frame.
 *
 * @param text        the recognised string
 * @param confidence  recognition score in {@code [0,1]} — callers filter on
 *                    {@code vidingest.ocr.min-confidence}
 * @param bbox        polygon corners as {@code [[x,y], ...]} (4 corners for standard
 *                    PaddleOCR output; we don't enforce length so quad/poly variants work)
 * @param language    detected language code, or null when the sidecar doesn't surface it
 */
public record OcrLine(
        String text,
        Float confidence,
        List<List<Double>> bbox,
        String language
) {
}
