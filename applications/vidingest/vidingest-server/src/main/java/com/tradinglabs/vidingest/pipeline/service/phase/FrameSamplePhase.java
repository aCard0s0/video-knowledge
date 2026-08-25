package com.tradinglabs.vidingest.pipeline.service.phase;

import com.tradinglabs.vidingest.config.FrameSamplingConfig;
import com.tradinglabs.vidingest.core.frames.service.FrameSamplingService;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.videos.domain.Video;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Frame-sampling phase (M3). Runs after {@link PersistPhase} so the video is in the DB
 * with a stable id and a known file path; sits before {@link OcrPhase} (M4) and the future
 * vision-captioning phase, both of which read from {@code vidingest_video_frames}.
 *
 * <p>Gates on:
 * <ul>
 *   <li>{@code vidingest.frames.enabled} (operator master switch)</li>
 *   <li>{@code !ctx.skipFrames} (per-run opt-out from REST/MCP/CLI)</li>
 * </ul>
 * Independent of {@code skipTranscription} — frames are useful for OCR / vision regardless
 * of whether the audio gets transcribed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public final class FrameSamplePhase implements PipelinePhase {

    private final FrameSamplingService frameSamplingService;
    private final FrameSamplingConfig frameSamplingConfig;

    @Override
    public PipelineRunPhase phase() {
        return PipelineRunPhase.FRAME_SAMPLE;
    }

    @Override
    public boolean applies(PipelinePhaseContext ctx) {
        return frameSamplingConfig.isEnabled() && !ctx.isSkipFrames();
    }

    @Override
    public void execute(PipelinePhaseContext ctx) {
        Video video = ctx.getVideo();
        if (video == null) {
            log.warn("FrameSamplePhase: ctx.video is null, skipping. runId={}, itemId={}",
                    ctx.getRunId(), ctx.getItemId());
            return;
        }
        frameSamplingService.sampleFrames(video);
    }
}
