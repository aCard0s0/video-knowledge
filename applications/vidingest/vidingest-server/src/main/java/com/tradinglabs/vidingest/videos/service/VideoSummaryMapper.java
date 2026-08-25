package com.tradinglabs.vidingest.videos.service;

import com.tradinglabs.vidingest.api.videos.VideoSummary;
import com.tradinglabs.vidingest.videos.domain.Video;
import org.springframework.stereotype.Component;

@Component
public class VideoSummaryMapper {

    public VideoSummary toSummary(Video video) {
        return new VideoSummary(
                video.getId() != null ? video.getId().toString() : "",
                video.getPipelineRun() != null && video.getPipelineRun().getId() != null ? video.getPipelineRun().getId().toString() : "",
                safe(video.getTitle()),
                safe(video.getSource()),
                safe(video.getSourceVideoId()),
                video.getStatus() != null ? video.getStatus().name() : "",
                safe(video.getFilePath()),
                safe(video.getChannelName()),
                video.getCreatedAt() != null ? video.getCreatedAt().toString() : ""
        );
    }

    private String safe(String value) {
        return value != null ? value : "";
    }
}

