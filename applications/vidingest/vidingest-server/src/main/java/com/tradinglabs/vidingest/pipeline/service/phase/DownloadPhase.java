package com.tradinglabs.vidingest.pipeline.service.phase;

import com.tradinglabs.vidingest.core.download.service.VideoDownloadService;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class DownloadPhase implements PipelinePhase {

    private final VideoDownloadService videoDownloadService;

    @Override
    public PipelineRunPhase phase() {
        return PipelineRunPhase.DOWNLOAD;
    }

    @Override
    public void execute(PipelinePhaseContext ctx) throws Exception {
        ctx.setFilePath(videoDownloadService.downloadVideoToDisk(ctx.getVideoUrl(), ctx.getMetadata()));
    }
}
