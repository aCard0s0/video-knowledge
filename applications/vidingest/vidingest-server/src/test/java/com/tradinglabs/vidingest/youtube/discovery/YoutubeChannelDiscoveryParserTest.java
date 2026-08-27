package com.tradinglabs.vidingest.youtube.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class YoutubeChannelDiscoveryParserTest {

    @Test
    void parsesFlatPlaylistDumpSingleJson() throws Exception {
        String json = """
                {
                  "channel_id": "UC_TEST",
                  "channel": "Example Channel",
                  "entries": [
                    { "id": "vid001", "title": "Video 1", "upload_date": "20260101" },
                    { "url": "vid002", "title": "Video 2", "timestamp": 1700000000 }
                  ]
                }
                """;

        var parser = new YoutubeChannelDiscoveryParser(new ObjectMapper());
        var result = parser.parse("https://www.youtube.com/@example", json);

        assertThat(result.channelUrl()).isEqualTo("https://www.youtube.com/@example");
        assertThat(result.channelId()).isEqualTo("UC_TEST");
        assertThat(result.channelName()).isEqualTo("Example Channel");
        assertThat(result.videos()).hasSize(2);

        var first = result.videos().getFirst();
        assertThat(first.youtubeVideoId()).isEqualTo("vid001");
        assertThat(first.watchUrl()).isEqualTo("https://www.youtube.com/watch?v=vid001");
        assertThat(first.title()).isEqualTo("Video 1");
        assertThat(first.publishedAt()).isEqualTo(OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));

        var second = result.videos().get(1);
        assertThat(second.youtubeVideoId()).isEqualTo("vid002");
        assertThat(second.watchUrl()).isEqualTo("https://www.youtube.com/watch?v=vid002");
        assertThat(second.title()).isEqualTo("Video 2");
        assertThat(second.publishedAt()).isEqualTo(OffsetDateTime.ofInstant(Instant.ofEpochSecond(1700000000), ZoneId.systemDefault()));
    }
}

