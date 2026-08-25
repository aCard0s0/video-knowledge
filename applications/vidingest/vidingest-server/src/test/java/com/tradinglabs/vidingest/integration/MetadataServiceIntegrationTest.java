package com.tradinglabs.vidingest.integration;

import com.tradinglabs.vidingest.core.download.service.MetadataService;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataServiceIntegrationTest extends BaseVidingestIntegrationTest {

    @Autowired
    private MetadataService metadataService;

    @Test
    void processMetadataCreatesAndUpdatesVideoRecord() {
        Map<String, Object> firstMetadata = Map.of(
                "extractor", "youtube",
                "id", "abc123",
                "title", "Initial Title",
                "description", "Initial description",
                "channel", "Trading Labs",
                "duration", 600,
                "upload_date", "20260315");

        Video created = metadataService.processMetadata(firstMetadata, "/videos/abc123.mp4");

        assertThat(created.getId()).isNotNull();
        assertThat(created.getSource()).isEqualTo("youtube");
        assertThat(created.getSourceVideoId()).isEqualTo("abc123");
        assertThat(created.getStatus()).isEqualTo(VideoStatus.DOWNLOADED);
        assertThat(created.getTitle()).isEqualTo("Initial Title");

        Map<String, Object> updatedMetadata = Map.of(
                "extractor", "youtube",
                "id", "abc123",
                "title", "Updated Title",
                "description", "Updated description",
                "channel", "Trading Labs",
                "duration", 601,
                "upload_date", "20260315");

        Video updated = metadataService.processMetadata(updatedMetadata, "/videos/abc123-new.mp4");

        assertThat(updated.getId()).isEqualTo(created.getId());
        assertThat(videoRepository.count()).isEqualTo(1);
        assertThat(updated.getTitle()).isEqualTo("Updated Title");
        assertThat(updated.getFilePath()).isEqualTo("/videos/abc123-new.mp4");
    }
}

