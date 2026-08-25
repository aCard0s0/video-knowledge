package com.tradinglabs.vidingest.youtube.discovery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class YoutubeChannelDiscoveryParser {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public YoutubeChannelDiscoveryResult parse(String channelUrl, String json) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        Map<String, Object> channelMetadata = objectMapper.convertValue(root, MAP);

        String channelId = text(root, "channel_id");
        if (channelId == null) {
            channelId = text(root, "uploader_id");
        }

        String channelName = text(root, "channel");
        if (channelName == null) {
            channelName = text(root, "uploader");
        }
        if (channelName == null) {
            channelName = text(root, "title");
        }

        List<YoutubeChannelDiscoveryResult.YoutubeVideoCandidate> videos = new ArrayList<>();
        JsonNode entries = root.get("entries");
        if (entries != null && entries.isArray()) {
            for (JsonNode entry : entries) {
                YoutubeChannelDiscoveryResult.YoutubeVideoCandidate candidate = parseEntry(entry);
                if (candidate != null) {
                    videos.add(candidate);
                }
            }
        } else {
            log.warn("yt-dlp channel discovery returned no entries. channelUrl={}", channelUrl);
        }

        return new YoutubeChannelDiscoveryResult(
                channelUrl,
                channelId,
                channelName,
                channelMetadata,
                List.copyOf(videos)
        );
    }

    private YoutubeChannelDiscoveryResult.YoutubeVideoCandidate parseEntry(JsonNode entry) {
        if (entry == null || entry.isNull()) {
            return null;
        }

        String id = text(entry, "id");
        if (id == null || id.isBlank()) {
            id = text(entry, "url");
        }
        if (id == null || id.isBlank()) {
            return null;
        }

        String title = text(entry, "title");
        LocalDateTime publishedAt = parsePublishedAt(entry);
        String watchUrl = watchUrl(id);

        Map<String, Object> metadata = objectMapper.convertValue(entry, MAP);

        return new YoutubeChannelDiscoveryResult.YoutubeVideoCandidate(
                id,
                title,
                publishedAt,
                watchUrl,
                metadata
        );
    }

    private static LocalDateTime parsePublishedAt(JsonNode entry) {
        String uploadDate = text(entry, "upload_date");
        if (uploadDate != null && uploadDate.length() == 8) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
                return LocalDateTime.parse(uploadDate + "0000", formatter);
            } catch (RuntimeException ignored) {
                // fall through
            }
        }

        JsonNode timestamp = entry.get("timestamp");
        if (timestamp != null && timestamp.canConvertToLong()) {
            try {
                return LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp.asLong()), ZoneId.systemDefault());
            } catch (RuntimeException ignored) {
                // fall through
            }
        }

        return null;
    }

    private static String watchUrl(String youtubeVideoId) {
        return "https://www.youtube.com/watch?v=" + youtubeVideoId;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return (s == null || s.isBlank()) ? null : s;
    }
}

