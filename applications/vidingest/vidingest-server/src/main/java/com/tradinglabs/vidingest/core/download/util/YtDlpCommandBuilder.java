package com.tradinglabs.vidingest.core.download.util;

import com.tradinglabs.vidingest.config.VideoDownloadConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.CommandLine;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builder for yt-dlp download commands
 * Encapsulates all command line construction logic with fluent API
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class YtDlpCommandBuilder {

    private final VideoDownloadConfig downloadConfig;

    /**
     * Build a download command with all configured options
     * 
     * @param videoUrl URL of the video to download
     * @param outputTemplate Output template (e.g., "path/to/file.%(ext)s" or "%(title)s.%(ext)s")
     * @param useRestrictFilenames Whether to use --restrict-filenames flag
     * @return Configured CommandLine ready for execution
     */
    public CommandLine buildDownloadCommand(String videoUrl, String outputTemplate, boolean useRestrictFilenames) {
        CommandLine cmdLine = new CommandLine(downloadConfig.getTool());
        
        addJavaScriptRuntimeOptions(cmdLine);
        addRemoteComponentsOptions(cmdLine);
        addFormatOptions(cmdLine);
        addOutputOptions(cmdLine, outputTemplate, useRestrictFilenames);
        addRetryOptions(cmdLine);
        addAudioPostprocessorOptions(cmdLine);
        addMetadataOptions(cmdLine);
        addSubtitleOptions(cmdLine);
        addThumbnailOptions(cmdLine);
        addAdditionalMetadataFiles(cmdLine);
        
        // Video URL must be last
        cmdLine.addArgument(videoUrl);
        
        return cmdLine;
    }

    /**
     * Build a metadata extraction command
     * 
     * @param videoUrl URL of the video
     * @return CommandLine for metadata extraction
     */
    public CommandLine buildMetadataCommand(String videoUrl) {
        CommandLine cmdLine = new CommandLine(downloadConfig.getTool());
        addJavaScriptRuntimeOptions(cmdLine);
        addRemoteComponentsOptions(cmdLine);
        cmdLine.addArgument("--dump-json");
        cmdLine.addArgument("--no-download");
        cmdLine.addArgument("--no-warnings");  // Suppress warnings to stdout
        cmdLine.addArgument("--quiet");  // Suppress progress messages
        cmdLine.addArgument(videoUrl);
        return cmdLine;
    }

    /**
     * Build a channel listing command that returns a single JSON payload with a bounded set of entries.
     *
     * <p>This uses yt-dlp's playlist extraction for channel/handle URLs and returns a flat list of entries
     * (id/title/date) without downloading media.</p>
     *
     * @param channelUrl YouTube channel URL (e.g. https://www.youtube.com/@handle)
     * @param playlistLimit Max number of entries to return (<=0 means no explicit limit)
     * @return CommandLine for channel listing
     */
    public CommandLine buildChannelListingCommand(String channelUrl, int playlistLimit) {
        CommandLine cmdLine = new CommandLine(downloadConfig.getTool());
        addJavaScriptRuntimeOptions(cmdLine);
        addRemoteComponentsOptions(cmdLine);

        cmdLine.addArgument("--dump-single-json");
        cmdLine.addArgument("--flat-playlist");
        cmdLine.addArgument("--no-download");
        cmdLine.addArgument("--no-warnings");
        cmdLine.addArgument("--quiet");

        if (playlistLimit > 0) {
            cmdLine.addArgument("--playlist-end");
            cmdLine.addArgument(String.valueOf(playlistLimit));
        }

        cmdLine.addArgument(ensureChannelListingUrl(channelUrl));
        return cmdLine;
    }

    // Private helper methods for different command sections

    private void addFormatOptions(CommandLine cmdLine) {
        // Format selection
        cmdLine.addArgument("--format");
        cmdLine.addArgument(downloadConfig.getFormat());
        
        // Container format (merge output format for video+audio combinations)
        if (isConfigured(downloadConfig.getContainer())) {
            cmdLine.addArgument("--merge-output-format");
            cmdLine.addArgument(downloadConfig.getContainer());
            log.debug("Using container format: {}", downloadConfig.getContainer());
        }
    }

    private void addOutputOptions(CommandLine cmdLine, String outputTemplate, boolean useRestrictFilenames) {
        // Output path
        cmdLine.addArgument("--output");
        cmdLine.addArgument(outputTemplate);
        
        // Filename restrictions
        if (useRestrictFilenames && downloadConfig.isRestrictFilenames()) {
            cmdLine.addArgument("--restrict-filenames");
        }
        if (downloadConfig.isWindowsFilenames()) {
            cmdLine.addArgument("--windows-filenames");
        }
    }

    private void addRetryOptions(CommandLine cmdLine) {
        cmdLine.addArgument("--retries");
        cmdLine.addArgument(String.valueOf(downloadConfig.getRetries()));
        
        // Concurrent fragments for faster downloads
        if (downloadConfig.getConcurrentFragments() > 1) {
            cmdLine.addArgument("--concurrent-fragments");
            cmdLine.addArgument(String.valueOf(downloadConfig.getConcurrentFragments()));
        }
    }

    private void addAudioPostprocessorOptions(CommandLine cmdLine) {
        if (!isConfigured(downloadConfig.getAudioCodec()) && !isConfigured(downloadConfig.getAudioBitrate())) {
            return;
        }

        List<String> ffmpegArgsList = new ArrayList<>();
        
        if (isConfigured(downloadConfig.getAudioCodec())) {
            String ffmpegCodec = mapAudioCodecToFfmpeg(downloadConfig.getAudioCodec());
            ffmpegArgsList.add("-c:a");
            ffmpegArgsList.add(ffmpegCodec);
            log.debug("Using audio codec: {} (ffmpeg: {})", downloadConfig.getAudioCodec(), ffmpegCodec);
        }
        
        if (isConfigured(downloadConfig.getAudioBitrate())) {
            ffmpegArgsList.add("-b:a");
            ffmpegArgsList.add(downloadConfig.getAudioBitrate() + "k");
            log.debug("Using audio bitrate: {} kbps", downloadConfig.getAudioBitrate());
        }
        
        String ffmpegArgs = String.join(" ", ffmpegArgsList);
        String postprocessorArgs = "ffmpeg:" + ffmpegArgs;
        
        cmdLine.addArgument("--postprocessor-args");
        cmdLine.addArgument(postprocessorArgs);
        log.debug("Postprocessor args: {}", postprocessorArgs);
    }

    private void addMetadataOptions(CommandLine cmdLine) {
        if (downloadConfig.isEmbedMetadata()) {
            cmdLine.addArgument("--embed-metadata");
        }
        if (downloadConfig.isEmbedThumbnail()) {
            cmdLine.addArgument("--embed-thumbnail");
        }
        if (downloadConfig.isEmbedChapters()) {
            cmdLine.addArgument("--embed-chapters");
        }
    }

    private void addSubtitleOptions(CommandLine cmdLine) {
        if (downloadConfig.isSubtitles()) {
            cmdLine.addArgument("--write-subs");
            
            if (isConfigured(downloadConfig.getSubtitleLanguages())) {
                cmdLine.addArgument("--sub-langs");
                cmdLine.addArgument(downloadConfig.getSubtitleLanguages());
            }
            
            if (!downloadConfig.isSubtitlesSeparate()) {
                cmdLine.addArgument("--embed-subs");
            }
            
            if (isConfigured(downloadConfig.getSubtitleFormat())) {
                cmdLine.addArgument("--sub-format");
                cmdLine.addArgument(downloadConfig.getSubtitleFormat());
            }
        } else if (downloadConfig.isEmbedSubtitles()) {
            cmdLine.addArgument("--embed-subs");
        }
    }

    private void addThumbnailOptions(CommandLine cmdLine) {
        if (downloadConfig.isThumbnail()) {
            cmdLine.addArgument("--write-thumbnail");
            
            if (isConfigured(downloadConfig.getThumbnailFormat())) {
                cmdLine.addArgument("--convert-thumbnails");
                cmdLine.addArgument(downloadConfig.getThumbnailFormat());
            }
        }
    }

    private void addAdditionalMetadataFiles(CommandLine cmdLine) {
        if (downloadConfig.isWriteInfoJson()) {
            cmdLine.addArgument("--write-info-json");
        }
        if (downloadConfig.isWriteDescription()) {
            cmdLine.addArgument("--write-description");
        }
    }

    private void addJavaScriptRuntimeOptions(CommandLine cmdLine) {
        if (!isConfigured(downloadConfig.getJsRuntimes())) {
            return;
        }
        cmdLine.addArgument("--js-runtimes");
        cmdLine.addArgument(downloadConfig.getJsRuntimes());
    }

    private void addRemoteComponentsOptions(CommandLine cmdLine) {
        if (!isConfigured(downloadConfig.getRemoteComponents())) {
            return;
        }
        cmdLine.addArgument("--remote-components");
        cmdLine.addArgument(downloadConfig.getRemoteComponents());
    }

    private static String ensureChannelListingUrl(String channelUrl) {
        if (channelUrl == null) {
            return null;
        }
        String trimmed = channelUrl.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        // A bare channel URL lists "tabs" (Videos/Live/Shorts) rather than video entries.
        // The /videos tab yields a flat-playlist of actual video IDs.
        if (trimmed.endsWith("/videos") || trimmed.endsWith("/shorts") || trimmed.endsWith("/streams") || trimmed.endsWith("/live")) {
            return trimmed;
        }
        return trimmed + "/videos";
    }

    /**
     * Map common audio codec names to ffmpeg codec names
     * 
     * @param codec User-friendly codec name (aac, mp3, opus, vorbis, m4a)
     * @return ffmpeg codec name
     */
    private String mapAudioCodecToFfmpeg(String codec) {
        if (codec == null || codec.isEmpty()) {
            return "copy"; // Default: don't re-encode
        }
        
        String normalized = codec.toLowerCase().trim();
        
        // Map common names to ffmpeg codec names
        return switch (normalized) {
            case "aac" -> "aac";
            case "acc" -> "aac"; // Handle typo in properties
            case "mp3" -> "libmp3lame";
            case "opus" -> "libopus";
            case "vorbis" -> "libvorbis";
            case "m4a" -> "aac"; // m4a container typically uses aac codec
            default -> {
                log.warn("Unknown audio codec '{}', using as-is. Valid options: aac, mp3, opus, vorbis, m4a", codec);
                yield normalized; // Use as-is, let ffmpeg handle it
            }
        };
    }

    /**
     * Check if a configuration value is set and not empty
     */
    private boolean isConfigured(String value) {
        return value != null && !value.isEmpty();
    }
}



