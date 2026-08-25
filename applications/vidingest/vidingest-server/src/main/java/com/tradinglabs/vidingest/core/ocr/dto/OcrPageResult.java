package com.tradinglabs.vidingest.core.ocr.dto;

import java.util.List;

/**
 * Parsed response from the {@code paddleocr-server} sidecar for a single frame upload.
 *
 * @param lines all detections, in PaddleOCR emission order (roughly top-to-bottom).
 *              The caller filters by {@code vidingest.ocr.min-confidence} before persisting.
 */
public record OcrPageResult(List<OcrLine> lines) {
}
