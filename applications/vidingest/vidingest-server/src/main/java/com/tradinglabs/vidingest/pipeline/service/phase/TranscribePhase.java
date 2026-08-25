package com.tradinglabs.vidingest.pipeline.service.phase;

import com.tradinglabs.vidingest.core.transcription.service.TranscriptionService;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class TranscribePhase implements PipelinePhase {

    private final TranscriptionService transcriptionService;
    private final VideoRepository videoRepository;

    @Override
    public PipelineRunPhase phase() {
        return PipelineRunPhase.TRANSCRIBE;
    }

    @Override
    public void execute(PipelinePhaseContext ctx) throws Exception {
        Video video = ctx.getVideo();
        video.setStatus(VideoStatus.TRANSCRIBING);
        video = videoRepository.save(video);
        ctx.setVideo(video);
        try {
            transcriptionService.transcribe(video);
        } catch (Exception e) {
            video.setStatus(VideoStatus.FAILED);
            videoRepository.save(video);
            throw e;
        }
    }
}
