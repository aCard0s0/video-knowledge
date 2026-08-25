package com.tradinglabs.vidingest.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for video download service
 * Maps properties from application.properties with prefix "vidingest.download"
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "vidingest.download")
public class VideoDownloadConfig {

    /**
     * Download tool executable name (yt-dlp, youtube-dl, etc.)
     */
    private String tool = "yt-dlp";

    /**
     * Format selector: Choose video/audio quality and format
     * Examples: "bestvideo+bestaudio", "best", "worst", etc.
     */
    private String format = "bestvideo+bestaudio";

    /**
     * Preferred container format (mp4, webm, mkv, etc.)
     */
    private String container;

    /**
     * Preferred audio codec (aac, mp3, opus, vorbis, m4a)
     */
    private String audioCodec;

    /**
     * Audio bitrate in kbps
     */
    private String audioBitrate;

    /**
     * Number of retries on download failure
     */
    private int retries = 3;

    /**
     * Maximum yt-dlp execution time in seconds.
     * Set to 0 to disable the watchdog timeout.
     */
    private long timeoutSeconds = 0;

    /**
     * Download subtitles
     */
    private boolean subtitles = false;

    /**
     * Subtitle languages (comma-separated)
     */
    private String subtitleLanguages = "";

    /**
     * Subtitle format (vtt, srt, ass, ttml, json3)
     */
    private String subtitleFormat = "vtt";

    /**
     * Write subtitles to separate files (if false, embeds in video)
     */
    private boolean subtitlesSeparate = true;

    /**
     * Download thumbnail
     */
    private boolean thumbnail = false;

    /**
     * Thumbnail format (jpg, png, webp)
     */
    private String thumbnailFormat = "jpg";

    /**
     * Embed metadata in video file
     */
    private boolean embedMetadata = true;

    /**
     * Embed subtitles in video file
     */
    private boolean embedSubtitles = false;

    /**
     * Embed thumbnail in video file
     */
    private boolean embedThumbnail = false;

    /**
     * Embed chapters in video file
     */
    private boolean embedChapters = false;

    /**
     * Write info JSON file
     */
    private boolean writeInfoJson = false;

    /**
     * Write description file
     */
    private boolean writeDescription = false;

    /**
     * Restrict filenames to ASCII characters only
     */
    private boolean restrictFilenames = true;

    /**
     * Use Windows-compatible filenames
     */
    private boolean windowsFilenames = false;

    /**
     * Number of concurrent fragments for faster downloads
     */
    private int concurrentFragments = 1;

    /**
     * Optional JavaScript runtimes configuration for yt-dlp extraction logic.
     * Example: "deno" or "deno:/usr/bin/deno" or a comma-separated list per yt-dlp docs.
     */
    private String jsRuntimes;

    /**
     * Optional remote component sources for yt-dlp extractor logic (YouTube EJS).
     * Example: "ejs:github" (recommended) or "ejs:npm" per yt-dlp docs.
     *
     * <p>Recent YouTube changes can require EJS remote components for reliable downloads.
     * Leave unset to keep yt-dlp default behavior.</p>
     */
    private String remoteComponents;
}



