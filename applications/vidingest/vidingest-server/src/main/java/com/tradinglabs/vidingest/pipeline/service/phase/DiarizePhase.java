package com.tradinglabs.vidingest.pipeline.service.phase;

import com.tradinglabs.vidingest.config.DiarizationConfig;
import com.tradinglabs.vidingest.core.diarization.service.DiarizationService;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.videos.domain.Video;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Speaker-diarization phase (M2): runs after {@link TranscribePhase} and tags each
 * transcription segment with a {@code speaker_id} from the {@code vidingest_speakers} table.
 *
 * <p>{@link #applies(PipelinePhaseContext)} gates on:
 * <ul>
 *   <li>{@code vidingest.diarization.enabled} (operator master switch)</li>
 *   <li>the run's own opt-out ({@code skipPhases} naming DIARIZE)</li>
 *   <li>TRANSCRIBE not being skipped — there is nothing useful to tag if the transcription
 *       phase did not run, so we skip diarization too rather than burning sidecar time on
 *       audio with no transcript to attach speakers to</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public final class DiarizePhase implements PipelinePhase {

    private final DiarizationService diarizationService;
    private final DiarizationConfig diarizationConfig;

    @Override
    public PipelineRunPhase phase() {
        return PipelineRunPhase.DIARIZE;
    }

    @Override
    public boolean applies(PipelinePhaseContext ctx) {
        return diarizationConfig.isEnabled()
                && !ctx.skipped(PipelineRunPhase.DIARIZE)
                && !ctx.skipped(PipelineRunPhase.TRANSCRIBE);
    }

    @Override
    public void execute(PipelinePhaseContext ctx) {
        Video video = ctx.getVideo();
        if (video == null) {
            log.warn("DiarizePhase: ctx.video is null, skipping. runId={}, itemId={}",
                    ctx.getRunId(), ctx.getItemId());
            return;
        }
        diarizationService.diarize(video);
    }
}
