package com.tradinglabs.vidingest.pipeline.service.phase;

import com.tradinglabs.vidingest.core.download.service.VideoDownloadService;
import com.tradinglabs.vidingest.core.download.util.MetadataExtractor;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.videos.exceptions.DuplicateVideoException;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public final class MetadataPhase implements PipelinePhase {

    private final VideoDownloadService videoDownloadService;
    private final VideoRepository videoRepository;

    @Override
    public PipelineRunPhase phase() {
        return PipelineRunPhase.METADATA;
    }

    @Override
    public void execute(PipelinePhaseContext ctx) throws Exception {
        Map<String, Object> metadata = videoDownloadService.extractMetadata(ctx.getVideoUrl());
        String source = MetadataExtractor.extractSource(metadata);
        String sourceVideoId = MetadataExtractor.extractSourceVideoId(metadata);
        if (videoRepository.existsBySourceAndSourceVideoId(source, sourceVideoId)) {
            throw new DuplicateVideoException(source, sourceVideoId);
        }
        ctx.setMetadata(metadata);
    }
}
