package com.tradinglabs.vidingest.pipeline.service.phase;

import com.tradinglabs.vidingest.core.download.service.MetadataService;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.pipeline.service.RunItemLifecycleService;
import com.tradinglabs.vidingest.pipeline.service.VideoLifecycleService;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class PersistPhase implements PipelinePhase {

    private final MetadataService metadataService;
    private final VideoLifecycleService videoLifecycleService;
    private final RunItemLifecycleService runItemLifecycleService;

    @Override
    public PipelineRunPhase phase() {
        return PipelineRunPhase.PERSIST;
    }

    @Override
    public void execute(PipelinePhaseContext ctx) {
        Video video = metadataService.createVideoFromMetadata(ctx.getMetadata(), ctx.getFilePath());
        video = videoLifecycleService.attachToPipelineRun(ctx.getRunId(), video, VideoStatus.DOWNLOADED);
        if (video.getId() != null) {
            runItemLifecycleService.attachVideo(ctx.getItemId(), video.getId());
        }
        ctx.setVideo(video);
    }
}
