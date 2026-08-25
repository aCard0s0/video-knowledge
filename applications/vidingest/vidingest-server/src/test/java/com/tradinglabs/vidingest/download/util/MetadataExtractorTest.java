package com.tradinglabs.vidingest.download.util;

import com.tradinglabs.vidingest.core.download.util.MetadataExtractor;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MetadataExtractorTest {

    @Test
    void extractSource_returnsNormalizedExtractor() {
        Map<String, Object> metadata = Map.of("extractor", "YouTube");
        assertEquals("youtube", MetadataExtractor.extractSource(metadata));
    }

    @Test
    void extractSource_fallsBackToWebpageUrl() {
        Map<String, Object> metadata = Map.of("webpage_url", "https://www.youtube.com/watch?v=abc");
        assertEquals("youtube", MetadataExtractor.extractSource(metadata));
    }

    @Test
    void extractSource_detectsVimeo() {
        Map<String, Object> metadata = Map.of("webpage_url", "https://vimeo.com/12345");
        assertEquals("vimeo", MetadataExtractor.extractSource(metadata));
    }

    @Test
    void extractSource_returnsUnknownWhenNoIndicators() {
        Map<String, Object> metadata = Map.of();
        assertEquals("unknown", MetadataExtractor.extractSource(metadata));
    }

    @Test
    void extractSourceVideoId_prefersIdField() {
        Map<String, Object> metadata = Map.of(
                "id", "dQw4w9WgXcQ",
                "display_id", "other-id",
                "webpage_url", "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertEquals("dQw4w9WgXcQ", MetadataExtractor.extractSourceVideoId(metadata));
    }

    @Test
    void extractSourceVideoId_fallsBackToDisplayId() {
        Map<String, Object> metadata = Map.of("display_id", "display-123");
        assertEquals("display-123", MetadataExtractor.extractSourceVideoId(metadata));
    }

    @Test
    void extractSourceVideoId_fallsBackToWebpageUrl() {
        Map<String, Object> metadata = Map.of("webpage_url", "https://example.com/v/xyz");
        assertEquals("https://example.com/v/xyz", MetadataExtractor.extractSourceVideoId(metadata));
    }

    @Test
    void extractSourceVideoId_returnsNullWhenEmpty() {
        Map<String, Object> metadata = Map.of();
        assertNull(MetadataExtractor.extractSourceVideoId(metadata));
    }

    @Test
    void extractTitle_returnsTitle() {
        Map<String, Object> metadata = Map.of("title", "Trading Strategy Explained");
        assertEquals("Trading Strategy Explained", MetadataExtractor.extractTitle(metadata));
    }

    @Test
    void extractTitle_returnsNullWhenMissing() {
        Map<String, Object> metadata = Map.of();
        assertNull(MetadataExtractor.extractTitle(metadata));
    }

    @Test
    void extractChannelName_triesFieldsInPriorityOrder() {
        Map<String, Object> withChannel = Map.of("channel", "TradingLabs", "uploader", "OtherName");
        assertEquals("TradingLabs", MetadataExtractor.extractChannelName(withChannel));

        Map<String, Object> withUploader = Map.of("uploader", "UploaderName");
        assertEquals("UploaderName", MetadataExtractor.extractChannelName(withUploader));

        Map<String, Object> withUploaderId = Map.of("uploader_id", "@tradingLabs");
        assertEquals("@tradingLabs", MetadataExtractor.extractChannelName(withUploaderId));
    }

    @Test
    void extractChannelName_returnsNullWhenNoFields() {
        Map<String, Object> metadata = Map.of();
        assertNull(MetadataExtractor.extractChannelName(metadata));
    }

    @Test
    void extractPublishDate_parsesUploadDate() {
        Map<String, Object> metadata = Map.of("upload_date", "20260315");
        LocalDateTime date = MetadataExtractor.extractPublishDate(metadata);
        assertNotNull(date);
        assertEquals(2026, date.getYear());
        assertEquals(3, date.getMonthValue());
        assertEquals(15, date.getDayOfMonth());
    }

    @Test
    void extractPublishDate_parsesTimestamp() {
        Map<String, Object> metadata = Map.of("timestamp", 1710500000L);
        LocalDateTime date = MetadataExtractor.extractPublishDate(metadata);
        assertNotNull(date);
        assertEquals(2024, date.getYear());
    }

    @Test
    void extractPublishDate_returnsNullWhenNoDates() {
        Map<String, Object> metadata = Map.of();
        assertNull(MetadataExtractor.extractPublishDate(metadata));
    }

    @Test
    void extractDateString_returnsUploadDate() {
        Map<String, Object> metadata = Map.of("upload_date", "20260315");
        assertEquals("20260315", MetadataExtractor.extractDateString(metadata));
    }

    @Test
    void extractDateString_fallsBackToCurrentDate() {
        Map<String, Object> metadata = Map.of();
        String dateStr = MetadataExtractor.extractDateString(metadata);
        assertNotNull(dateStr);
        assertEquals(8, dateStr.length());
    }

    @Test
    void extractString_returnsNullForMissingKey() {
        Map<String, Object> metadata = Map.of();
        assertNull(MetadataExtractor.extractString(metadata, "missing"));
    }

    @Test
    void extractInteger_handlesNumbers() {
        Map<String, Object> metadata = Map.of("duration", 300);
        assertEquals(300, MetadataExtractor.extractInteger(metadata, "duration"));
    }

    @Test
    void extractInteger_returnsNullForNonNumber() {
        Map<String, Object> metadata = Map.of("duration", "not-a-number");
        assertNull(MetadataExtractor.extractInteger(metadata, "duration"));
    }

    @Test
    void extractInteger_returnsNullForMissingKey() {
        Map<String, Object> metadata = Map.of();
        assertNull(MetadataExtractor.extractInteger(metadata, "duration"));
    }

    @Test
    void extractChannelName_skipsEmptyStrings() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("channel", "");
        metadata.put("uploader", "RealChannel");
        assertEquals("RealChannel", MetadataExtractor.extractChannelName(metadata));
    }

    @Test
    void extractPublishDate_prefersUploadDateOverTimestamp() {
        Map<String, Object> metadata = Map.of(
                "upload_date", "20260101",
                "timestamp", 1710500000L);
        LocalDateTime date = MetadataExtractor.extractPublishDate(metadata);
        assertNotNull(date);
        assertEquals(2026, date.getYear());
        assertEquals(1, date.getMonthValue());
    }

    @Test
    void extractSource_handlesNullExtractorWithYoutubeUrl() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("extractor", null);
        metadata.put("webpage_url", "https://youtu.be/abc123");
        assertEquals("youtube", MetadataExtractor.extractSource(metadata));
    }
}
