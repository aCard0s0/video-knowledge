package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.commons.PhaseFailureException;
import com.tradinglabs.vidingest.core.transcription.service.TranscriptionFailureException;
import com.tradinglabs.vidingest.pipeline.domain.PipelineErrorCode;
import com.tradinglabs.vidingest.videos.exceptions.DuplicateVideoException;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class PipelineErrorClassifier {

    public PipelineErrorCode classify(Throwable t) {
        if (t instanceof DuplicateVideoException) {
            return PipelineErrorCode.DUPLICATE_VIDEO;
        }
        if (t instanceof TranscriptionFailureException) {
            return PipelineErrorCode.TRANSCRIPTION_FAILURE;
        }
        // Every other phase failure (diarization, frame sampling, fusion, OCR, knowledge)
        // is an upstream tool or sidecar that did not deliver. Matching on the supertype
        // means a new phase is classified correctly without touching this class.
        if (t instanceof PhaseFailureException) {
            return PipelineErrorCode.UPSTREAM_TOOL_FAILURE;
        }
        if (t instanceof IOException) {
            return PipelineErrorCode.UPSTREAM_TOOL_FAILURE;
        }
        return PipelineErrorCode.UNEXPECTED;
    }
}
