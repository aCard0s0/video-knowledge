package com.tradinglabs.vidingest.videos.service;

import com.tradinglabs.vidingest.api.videos.VideoSummary;
import com.tradinglabs.vidingest.videos.domain.Video;
import org.springframework.stereotype.Component;

@Component
public class VideoSummaryMapper {

    public VideoSummary toSummary(Video video) {
        return new VideoSummary(
                video.getId(),
                video.getPipelineRun() != null ? video.getPipelineRun().getId() : null,
                video.getTitle(),
                video.getSource(),
                video.getSourceVideoId(),
                video.getStatus() != null ? video.getStatus().name() : null,
                video.getFilePath(),
                video.getChannelName(),
                video.getCreatedAt()
        );
    }
}
