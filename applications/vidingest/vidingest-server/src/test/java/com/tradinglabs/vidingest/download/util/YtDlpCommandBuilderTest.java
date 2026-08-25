package com.tradinglabs.vidingest.download.util;

import com.tradinglabs.vidingest.config.VideoDownloadConfig;
import com.tradinglabs.vidingest.core.download.util.YtDlpCommandBuilder;
import org.apache.commons.exec.CommandLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YtDlpCommandBuilderTest {

    private VideoDownloadConfig config;
    private YtDlpCommandBuilder builder;

    @BeforeEach
    void setUp() {
        config = new VideoDownloadConfig();
        builder = new YtDlpCommandBuilder(config);
    }

    @Test
    void buildDownloadCommand_includesFormatAndOutput() {
        CommandLine cmd = builder.buildDownloadCommand(
                "https://www.youtube.com/watch?v=abc", "/output/path.%(ext)s", true);

        List<String> args = Arrays.asList(cmd.toStrings());
        assertTrue(args.contains("--format"));
        assertTrue(args.contains("bestvideo+bestaudio"));
        assertTrue(args.contains("--output"));
        assertTrue(args.contains("/output/path.%(ext)s"));
    }

    @Test
    void buildDownloadCommand_includesRetries() {
        config.setRetries(5);
        CommandLine cmd = builder.buildDownloadCommand(
                "https://www.youtube.com/watch?v=abc", "out.%(ext)s", true);

        List<String> args = Arrays.asList(cmd.toStrings());
        assertTrue(args.contains("--retries"));
        assertTrue(args.contains("5"));
    }

    @Test
    void buildDownloadCommand_includesContainerFormat() {
        config.setContainer("mkv");
        CommandLine cmd = builder.buildDownloadCommand(
                "https://www.youtube.com/watch?v=abc", "out.%(ext)s", true);

        List<String> args = Arrays.asList(cmd.toStrings());
        assertTrue(args.contains("--merge-output-format"));
        assertTrue(args.contains("mkv"));
    }

    @Test
    void buildDownloadCommand_omitsContainerWhenNull() {
        config.setContainer(null);
        CommandLine cmd = builder.buildDownloadCommand(
                "https://www.youtube.com/watch?v=abc", "out.%(ext)s", true);

        List<String> args = Arrays.asList(cmd.toStrings());
        assertFalse(args.contains("--merge-output-format"));
    }

    @Test
    void buildDownloadCommand_addsRestrictFilenames() {
        config.setRestrictFilenames(true);
        CommandLine cmd = builder.buildDownloadCommand(
                "https://www.youtube.com/watch?v=abc", "out.%(ext)s", true);

        List<String> args = Arrays.asList(cmd.toStrings());
        assertTrue(args.contains("--restrict-filenames"));
    }

    @Test
    void buildDownloadCommand_skipsRestrictFilenamesWhenFlagIsFalse() {
        config.setRestrictFilenames(true);
        CommandLine cmd = builder.buildDownloadCommand(
                "https://www.youtube.com/watch?v=abc", "out.%(ext)s", false);

        List<String> args = Arrays.asList(cmd.toStrings());
        assertFalse(args.contains("--restrict-filenames"));
    }

    @Test
    void buildDownloadCommand_addsSubtitleOptions() {
        config.setSubtitles(true);
        config.setSubtitleLanguages("en,pt");
        config.setSubtitleFormat("srt");
        config.setSubtitlesSeparate(true);

        CommandLine cmd = builder.buildDownloadCommand(
                "https://www.youtube.com/watch?v=abc", "out.%(ext)s", true);

        List<String> args = Arrays.asList(cmd.toStrings());
        assertTrue(args.contains("--write-subs"));
        assertTrue(args.contains("--sub-langs"));
        assertTrue(args.contains("en,pt"));
        assertTrue(args.contains("--sub-format"));
        assertTrue(args.contains("srt"));
        assertFalse(args.contains("--embed-subs"));
    }

    @Test
    void buildDownloadCommand_embedsSubtitlesWhenNotSeparate() {
        config.setSubtitles(true);
        config.setSubtitlesSeparate(false);

        CommandLine cmd = builder.buildDownloadCommand(
                "https://www.youtube.com/watch?v=abc", "out.%(ext)s", true);

        List<String> args = Arrays.asList(cmd.toStrings());
        assertTrue(args.contains("--write-subs"));
        assertTrue(args.contains("--embed-subs"));
    }

    @Test
    void buildDownloadCommand_addsThumbnailOptions() {
        config.setThumbnail(true);
        config.setThumbnailFormat("png");

        CommandLine cmd = builder.buildDownloadCommand(
                "https://www.youtube.com/watch?v=abc", "out.%(ext)s", true);

        List<String> args = Arrays.asList(cmd.toStrings());
        assertTrue(args.contains("--write-thumbnail"));
        assertTrue(args.contains("--convert-thumbnails"));
        assertTrue(args.contains("png"));
    }

    @Test
    void buildDownloadCommand_addsEmbedMetadata() {
        config.setEmbedMetadata(true);
        CommandLine cmd = builder.buildDownloadCommand(
                "https://www.youtube.com/watch?v=abc", "out.%(ext)s", true);

        List<String> args = Arrays.asList(cmd.toStrings());
        assertTrue(args.contains("--embed-metadata"));
    }

    @Test
    void buildDownloadCommand_addsConcurrentFragments() {
        config.setConcurrentFragments(4);
        CommandLine cmd = builder.buildDownloadCommand(
                "https://www.youtube.com/watch?v=abc", "out.%(ext)s", true);

        List<String> args = Arrays.asList(cmd.toStrings());
        assertTrue(args.contains("--concurrent-fragments"));
        assertTrue(args.contains("4"));
    }

    @Test
    void buildDownloadCommand_urlIsLastArgument() {
        String url = "https://www.youtube.com/watch?v=abc";
        CommandLine cmd = builder.buildDownloadCommand(url, "out.%(ext)s", true);

        String[] args = cmd.toStrings();
        assertEquals(url, args[args.length - 1]);
    }

    @Test
    void buildMetadataCommand_includesCorrectFlags() {
        CommandLine cmd = builder.buildMetadataCommand("https://www.youtube.com/watch?v=abc");

        List<String> args = Arrays.asList(cmd.toStrings());
        assertTrue(args.contains("--dump-json"));
        assertTrue(args.contains("--no-download"));
        assertTrue(args.contains("--no-warnings"));
        assertTrue(args.contains("--quiet"));
    }

    @Test
    void buildDownloadCommand_addsRemoteComponentsWhenConfigured() {
        config.setRemoteComponents("ejs:github");
        CommandLine cmd = builder.buildDownloadCommand("https://www.youtube.com/watch?v=abc", "out.%(ext)s", true);

        List<String> args = Arrays.asList(cmd.toStrings());
        assertTrue(args.contains("--remote-components"));
        assertTrue(args.contains("ejs:github"));
    }

    @Test
    void buildMetadataCommand_addsRemoteComponentsWhenConfigured() {
        config.setRemoteComponents("ejs:github");
        CommandLine cmd = builder.buildMetadataCommand("https://www.youtube.com/watch?v=abc");

        List<String> args = Arrays.asList(cmd.toStrings());
        assertTrue(args.contains("--remote-components"));
        assertTrue(args.contains("ejs:github"));
    }

    @Test
    void buildMetadataCommand_urlIsLast() {
        String url = "https://www.youtube.com/watch?v=abc";
        CommandLine cmd = builder.buildMetadataCommand(url);

        String[] args = cmd.toStrings();
        assertEquals(url, args[args.length - 1]);
    }

    @Test
    void buildDownloadCommand_addsAudioPostprocessorOptions() {
        config.setAudioCodec("aac");
        config.setAudioBitrate("256");

        CommandLine cmd = builder.buildDownloadCommand(
                "https://www.youtube.com/watch?v=abc", "out.%(ext)s", true);

        List<String> args = Arrays.asList(cmd.toStrings());
        assertTrue(args.contains("--postprocessor-args"));
    }

    @Test
    void buildDownloadCommand_addsWriteInfoJsonAndDescription() {
        config.setWriteInfoJson(true);
        config.setWriteDescription(true);

        CommandLine cmd = builder.buildDownloadCommand(
                "https://www.youtube.com/watch?v=abc", "out.%(ext)s", true);

        List<String> args = Arrays.asList(cmd.toStrings());
        assertTrue(args.contains("--write-info-json"));
        assertTrue(args.contains("--write-description"));
    }
}
