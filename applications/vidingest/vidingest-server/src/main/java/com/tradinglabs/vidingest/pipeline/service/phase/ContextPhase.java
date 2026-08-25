package com.tradinglabs.vidingest.pipeline.service.phase;

import com.tradinglabs.vidingest.config.VideoSearchConfig;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.search.service.embedding.ContextChunkGenerationService;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public final class ContextPhase implements PipelinePhase {

    private final ContextChunkGenerationService contextChunkGenerationService;
    private final VideoRepository videoRepository;
    private final VideoSearchConfig searchConfig;

    @Override
    public PipelineRunPhase phase() {
        return PipelineRunPhase.CONTEXT;
    }

    @Override
    public boolean applies(PipelinePhaseContext ctx) {
        return !ctx.isSkipContext() && searchConfig.isSemanticEnabled();
    }

    @Override
    public void execute(PipelinePhaseContext ctx) throws Exception {
        Video video = ctx.getVideo();
        video.setStatus(VideoStatus.PROCESSING);
        video = videoRepository.save(video);
        ctx.setVideo(video);
        try {
            int chunks = contextChunkGenerationService.regenerateFor(video);
            ctx.setRowsAffected(chunks);
            log.info("Context generation complete: videoId={}, chunks={}", video.getId(), chunks);
        } catch (Exception e) {
            video.setStatus(VideoStatus.FAILED);
            videoRepository.save(video);
            throw e;
        }
    }
}
