package com.tradinglabs.vidingest.core.download.util;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Utility class for extracting metadata from video metadata maps
 * Centralizes all metadata extraction logic to avoid duplication
 */
@Slf4j
public final class MetadataExtractor {

    private MetadataExtractor() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Extract string value from metadata map
     */
    public static String extractString(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * Extract integer value from metadata map
     */
    public static Integer extractInteger(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    /**
     * Extract video source platform from metadata
     * 
     * @param metadata Video metadata map
     * @return Platform name (youtube, vimeo, etc.) or "unknown"
     */
    public static String extractSource(Map<String, Object> metadata) {
        String extractor = extractString(metadata, "extractor");
        if (extractor != null) {
            return normalizeExtractorName(extractor);
        }

        String webpage = extractString(metadata, "webpage_url");
        if (webpage != null) {
            return detectSourceFromUrl(webpage);
        }

        return "unknown";
    }

    /**
     * Extract source video ID from metadata
     * 
     * @param metadata Video metadata map
     * @return Video ID or webpage URL as fallback
     */
    public static String extractSourceVideoId(Map<String, Object> metadata) {
        String id = extractString(metadata, "id");
        if (id != null && !id.isEmpty()) {
            return id;
        }

        String displayId = extractString(metadata, "display_id");
        if (displayId != null && !displayId.isEmpty()) {
            return displayId;
        }

        return extractString(metadata, "webpage_url");
    }

    /**
     * Extract video title from metadata
     */
    public static String extractTitle(Map<String, Object> metadata) {
        return extractString(metadata, "title");
    }

    /**
     * Extract channel name from metadata
     * Tries multiple fields in priority order: channel, uploader, channel_name, uploader_id
     * 
     * @param metadata Video metadata map
     * @return Channel name or null if not found
     */
    public static String extractChannelName(Map<String, Object> metadata) {
        // Try common field names for channel/uploader in priority order
        String[] channelFields = {"channel", "uploader", "channel_name", "uploader_id"};
        
        for (String field : channelFields) {
            String value = extractString(metadata, field);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }

        return null;
    }

    /**
     * Extract publish date from metadata
     * Prioritizes publish/upload date fields over timestamp
     * 
     * @param metadata Video metadata map
     * @return OffsetDateTime of publish date at UTC, or null if not found
     */
    public static OffsetDateTime extractPublishDate(Map<String, Object> metadata) {
        // Try upload_date first (YYYYMMDD format)
        OffsetDateTime uploadDate = parseUploadDate(metadata, "upload_date");
        if (uploadDate != null) {
            return uploadDate;
        }

        // Try release_date
        OffsetDateTime releaseDate = parseUploadDate(metadata, "release_date");
        if (releaseDate != null) {
            return releaseDate;
        }

        // Try timestamp (Unix epoch seconds)
        return parseTimestamp(metadata);
    }

    /**
     * Extract publish date as YYYYMMDD string for filename purposes
     * 
     * @param metadata Video metadata map
     * @return Date string in YYYYMMDD format or current date as fallback
     */
    public static String extractDateString(Map<String, Object> metadata) {
        // Try upload_date first (already in YYYYMMDD format)
        String uploadDate = extractString(metadata, "upload_date");
        if (uploadDate != null && uploadDate.length() == 8 && isValidDateString(uploadDate)) {
            log.debug("Using upload_date (publish date) from metadata: {}", uploadDate);
            return uploadDate;
        }

        // Try release_date
        String releaseDate = extractString(metadata, "release_date");
        if (releaseDate != null && releaseDate.length() == 8 && isValidDateString(releaseDate)) {
            log.debug("Using release_date from metadata: {}", releaseDate);
            return releaseDate;
        }

        // Try timestamp (Unix epoch seconds)
        String timestampDate = formatTimestampAsDate(metadata);
        if (timestampDate != null) {
            return timestampDate;
        }

        // Fallback to current date
        String currentDate = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        log.warn("No publish date found in metadata, using current date: {}", currentDate);
        return currentDate;
    }

    // Private helper methods

    private static String normalizeExtractorName(String extractor) {
        return extractor.toLowerCase();
    }

    private static String detectSourceFromUrl(String webpage) {
        if (webpage.contains("youtube.com") || webpage.contains("youtu.be")) {
            return "youtube";
        } else if (webpage.contains("vimeo.com")) {
            return "vimeo";
        }
        return "unknown";
    }

    /** {@code upload_date} is a bare YYYYMMDD calendar date; YouTube means it as UTC. */
    private static OffsetDateTime parseUploadDate(Map<String, Object> metadata, String field) {
        String dateStr = extractString(metadata, field);
        if (dateStr != null && dateStr.length() == 8) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
                return LocalDateTime.parse(dateStr + "0000", formatter).atOffset(ZoneOffset.UTC);
            } catch (Exception e) {
                log.warn("Failed to parse {}: {}", field, dateStr, e);
            }
        }
        return null;
    }

    private static OffsetDateTime parseTimestamp(Map<String, Object> metadata) {
        Object timestamp = metadata.get("timestamp");
        if (timestamp instanceof Number) {
            try {
                // ZoneId.systemDefault() here made the same video's published_at differ by
                // an hour between a UTC container and a host in a non-UTC zone. The epoch is
                // already an instant; UTC is the only reading that does not invent a zone.
                return Instant.ofEpochSecond(((Number) timestamp).longValue()).atOffset(ZoneOffset.UTC);
            } catch (Exception e) {
                log.warn("Failed to format timestamp: {}", timestamp, e);
            }
        }
        return null;
    }

    private static boolean isValidDateString(String dateStr) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
            LocalDateTime.parse(dateStr + "0000", formatter);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String formatTimestampAsDate(Map<String, Object> metadata) {
        OffsetDateTime dateTime = parseTimestamp(metadata);
        if (dateTime != null) {
            String dateStr = dateTime.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            log.debug("Using timestamp (publish timestamp) from metadata: {}", dateStr);
            return dateStr;
        }
        return null;
    }
}



