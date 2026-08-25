package com.tradinglabs.vidingest.pipeline.service;

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
        if (t instanceof IOException) {
            return PipelineErrorCode.UPSTREAM_TOOL_FAILURE;
        }
        return PipelineErrorCode.UNEXPECTED;
    }
}
