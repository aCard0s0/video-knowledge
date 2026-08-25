package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.core.diarization.service.DiarizationFailureException;
import com.tradinglabs.vidingest.core.frames.service.FrameSamplingFailureException;
import com.tradinglabs.vidingest.core.fusion.service.FusionFailureException;
import com.tradinglabs.vidingest.core.knowledge.service.KnowledgeExtractionFailureException;
import com.tradinglabs.vidingest.core.ocr.service.OcrFailureException;
import com.tradinglabs.vidingest.core.transcription.service.TranscriptionFailureException;
import com.tradinglabs.vidingest.pipeline.domain.PipelineErrorCode;
import com.tradinglabs.vidingest.videos.exceptions.DuplicateVideoException;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The classifier used to name three exception types individually, so the five sibling
 * {@code *FailureException}s landed on UNEXPECTED — indistinguishable, in the run item's error
 * code, from a null-pointer bug. It now matches the {@code PhaseFailureException} supertype.
 */
class PipelineErrorClassifierTest {

    private final PipelineErrorClassifier classifier = new PipelineErrorClassifier();

    @Test
    void classifiesEveryPhaseFailureAsAnUpstreamToolFailure() {
        assertThat(classifier.classify(new DiarizationFailureException("pyannote down")))
                .isEqualTo(PipelineErrorCode.UPSTREAM_TOOL_FAILURE);
        assertThat(classifier.classify(new FrameSamplingFailureException("ffmpeg exit 1")))
                .isEqualTo(PipelineErrorCode.UPSTREAM_TOOL_FAILURE);
        assertThat(classifier.classify(new FusionFailureException("bad window")))
                .isEqualTo(PipelineErrorCode.UPSTREAM_TOOL_FAILURE);
        assertThat(classifier.classify(new KnowledgeExtractionFailureException("ollama down")))
                .isEqualTo(PipelineErrorCode.UPSTREAM_TOOL_FAILURE);
        assertThat(classifier.classify(new OcrFailureException("paddleocr down")))
                .isEqualTo(PipelineErrorCode.UPSTREAM_TOOL_FAILURE);
    }

    @Test
    void keepsTranscriptionMoreSpecificThanTheSupertype() {
        // TranscriptionFailureException is also a PhaseFailureException — the specific arm has
        // to stay ahead of the supertype arm or this silently degrades to UPSTREAM_TOOL_FAILURE.
        assertThat(classifier.classify(new TranscriptionFailureException("whisper down")))
                .isEqualTo(PipelineErrorCode.TRANSCRIPTION_FAILURE);
    }

    @Test
    void classifiesDuplicatesAndPlainIoUnchanged() {
        assertThat(classifier.classify(new DuplicateVideoException("youtube", "abc123")))
                .isEqualTo(PipelineErrorCode.DUPLICATE_VIDEO);
        assertThat(classifier.classify(new IOException("connection reset")))
                .isEqualTo(PipelineErrorCode.UPSTREAM_TOOL_FAILURE);
    }

    @Test
    void classifiesGenuineBugsAsUnexpected() {
        assertThat(classifier.classify(new NullPointerException()))
                .isEqualTo(PipelineErrorCode.UNEXPECTED);
        assertThat(classifier.classify(new IllegalStateException("bad state")))
                .isEqualTo(PipelineErrorCode.UNEXPECTED);
    }
}
