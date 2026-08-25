package com.tradinglabs.vidingest.core.frames.service;

import com.tradinglabs.vidingest.config.FrameSamplingConfig;
import com.tradinglabs.vidingest.core.frames.domain.SamplingReason;
import com.tradinglabs.vidingest.core.frames.domain.VideoFrame;
import com.tradinglabs.vidingest.core.frames.repo.VideoFrameRepository;
import com.tradinglabs.vidingest.videos.domain.Video;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionOperations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link FrameSamplingService} without spawning ffmpeg by subclassing the
 * service and overriding {@code runFfmpeg(...)} to drop a deterministic stderr blob and
 * fabricate a corresponding set of JPGs on disk. Keeps tests hermetic and fast.
 */
class FrameSamplingServiceTest {

    private FrameSamplingConfig config;
    private VideoFrameRepository repo;

    @BeforeEach
    void setUp() {
        config = new FrameSamplingConfig();
        config.setEnabled(true);
        config.setIntervalSeconds(10.0);
        config.setSceneChangeThreshold(0.35);
        config.setMaxFramesPerVideo(600);
        config.setFramesDirName("frames");
        config.setIntervalClassificationTolerance(0.5);
        repo = mock(VideoFrameRepository.class);
        when(repo.saveAll(any())).thenAnswer(inv -> {
            // saveAll returns whatever it was given; tests assert against the captured list
            // via the persistAndReturn path.
            Iterable<VideoFrame> in = inv.getArgument(0);
            List<VideoFrame> out = new ArrayList<>();
            in.forEach(out::add);
            return out;
        });
    }

    @Test
    void classifyReasonTagsBoundaryFramesAsInterval() {
        // 0s, 10s, 20s — at exact boundaries → INTERVAL
        assertThat(FrameSamplingService.classifyReason(0.0, 10.0, 0.5)).isEqualTo(SamplingReason.INTERVAL);
        assertThat(FrameSamplingService.classifyReason(10.0, 10.0, 0.5)).isEqualTo(SamplingReason.INTERVAL);
        assertThat(FrameSamplingService.classifyReason(20.4, 10.0, 0.5)).isEqualTo(SamplingReason.INTERVAL);
    }

    @Test
    void classifyReasonTagsOffBoundaryFramesAsSceneChange() {
        // 3.2s, 7.7s, 13.5s — comfortably away from any 10s boundary → SCENE_CHANGE
        assertThat(FrameSamplingService.classifyReason(3.2, 10.0, 0.5)).isEqualTo(SamplingReason.SCENE_CHANGE);
        assertThat(FrameSamplingService.classifyReason(7.7, 10.0, 0.5)).isEqualTo(SamplingReason.SCENE_CHANGE);
        assertThat(FrameSamplingService.classifyReason(13.5, 10.0, 0.5)).isEqualTo(SamplingReason.SCENE_CHANGE);
    }

    @Test
    void classifyReasonFallsBackToKeyframeWhenIntervalDisabled() {
        // interval <= 0 means "no fixed-interval sampling"; any kept frame must be a key/scene
        assertThat(FrameSamplingService.classifyReason(5.0, 0.0, 0.5)).isEqualTo(SamplingReason.KEYFRAME);
        assertThat(FrameSamplingService.classifyReason(123.456, -1.0, 0.5)).isEqualTo(SamplingReason.KEYFRAME);
    }

    @Test
    void buildSelectExpressionCombinesSceneAndIntervalCriteria() {
        FrameSamplingService svc = new FrameSamplingService(config, repo, TransactionOperations.withoutTransaction());
        String expr = svc.buildSelectExpression();
        // Both terms present, comma-escaped inside function args, joined by `+` (OR).
        assertThat(expr).contains("gt(scene\\,0.35)");
        assertThat(expr).contains("isnan(prev_selected_t)+gte(t-prev_selected_t\\,10.0)");
        assertThat(expr).contains("+");
    }

    @Test
    void buildSelectExpressionWithSceneOnly() {
        config.setIntervalSeconds(0);
        FrameSamplingService svc = new FrameSamplingService(config, repo, TransactionOperations.withoutTransaction());
        String expr = svc.buildSelectExpression();
        assertThat(expr).isEqualTo("gt(scene\\,0.35)");
    }

    @Test
    void buildSelectExpressionWithIntervalOnly() {
        config.setSceneChangeThreshold(0);
        FrameSamplingService svc = new FrameSamplingService(config, repo, TransactionOperations.withoutTransaction());
        String expr = svc.buildSelectExpression();
        assertThat(expr).isEqualTo("isnan(prev_selected_t)+gte(t-prev_selected_t\\,10.0)");
    }

    @Test
    void buildSelectExpressionDegenerateConfigYieldsZero() {
        // Both heuristics off — keep nothing rather than every frame.
        config.setSceneChangeThreshold(0);
        config.setIntervalSeconds(0);
        FrameSamplingService svc = new FrameSamplingService(config, repo, TransactionOperations.withoutTransaction());
        assertThat(svc.buildSelectExpression()).isEqualTo("0");
    }

    @Test
    void framesDirForResolvesSubdirInsidePerVideoFolder() {
        FrameSamplingService svc = new FrameSamplingService(config, repo, TransactionOperations.withoutTransaction());

        Path videoFile = Path.of("/data/videos/MyChannel/20260512.cool-video/20260512.cool-video.mp4");
        Path framesDir = svc.framesDirFor(videoFile);

        assertThat(framesDir).isEqualTo(Path.of("/data/videos/MyChannel/20260512.cool-video/frames"));
    }

    @Test
    void framesDirForUsesCustomDirName() {
        config.setFramesDirName("thumbs");
        FrameSamplingService svc = new FrameSamplingService(config, repo, TransactionOperations.withoutTransaction());

        assertThat(svc.framesDirFor(Path.of("/x/show/episode/episode.mkv")))
                .isEqualTo(Path.of("/x/show/episode/thumbs"));
    }

    @Test
    void sampleFramesFailsCleanlyWhenVideoFileMissing(@TempDir Path tmp) {
        FrameSamplingService svc = new FrameSamplingService(config, repo, TransactionOperations.withoutTransaction());

        Video video = video(tmp.resolve("does-not-exist.mp4").toString());

        assertThatThrownBy(() -> svc.sampleFrames(video))
                .isInstanceOf(FrameSamplingFailureException.class)
                .hasMessageContaining("Video file does not exist");
    }

    @Test
    void sampleFramesReturnsEmptyWhenFilePathBlank() {
        FrameSamplingService svc = new FrameSamplingService(config, repo, TransactionOperations.withoutTransaction());

        Video video = video("");
        List<VideoFrame> out = svc.sampleFrames(video);

        assertThat(out).isEmpty();
        verify(repo, never()).saveAll(any());
    }

    @Test
    void sampleFramesEndToEndPersistsFramesPairedWithTimestamps(@TempDir Path tmp) throws Exception {
        Path videoFile = tmp.resolve("v.mp4");
        Files.writeString(videoFile, "fake mp4");

        // Stub ffmpeg: drop showinfo lines for 3 frames at t=0,10,13 and create matching JPGs.
        String cannedStderr = String.join("\n",
                "[Parsed_showinfo_1 @ 0xff] n:0 pts:0 pts_time:0.0 s:640x360 fmt:yuv420p",
                "[Parsed_showinfo_1 @ 0xff] n:1 pts:96000 pts_time:10.0 s:640x360",
                "[Parsed_showinfo_1 @ 0xff] n:2 pts:124800 pts_time:13.0 s:640x360"
        );

        FrameSamplingService svc = new FrameSamplingService(config, repo, TransactionOperations.withoutTransaction()) {
            @Override
            protected String runFfmpeg(Path inputVideo, Path framesDir) {
                // Pretend ffmpeg wrote three JPGs into the prepared dir.
                writeFakeJpg(framesDir.resolve("0001.jpg"));
                writeFakeJpg(framesDir.resolve("0002.jpg"));
                writeFakeJpg(framesDir.resolve("0003.jpg"));
                return cannedStderr;
            }
        };

        Video video = video(videoFile.toString());
        List<VideoFrame> result = svc.sampleFrames(video);

        assertThat(result).hasSize(3);

        // Frame 0 at t=0 — boundary → INTERVAL
        assertThat(result.get(0).getTimestampSeconds()).isCloseTo(0.0, within(1e-6));
        assertThat(result.get(0).getSamplingReason()).isEqualTo(SamplingReason.INTERVAL);
        assertThat(result.get(0).getWidth()).isEqualTo(640);
        assertThat(result.get(0).getHeight()).isEqualTo(360);
        assertThat(result.get(0).getFrameIndex()).isZero();

        // Frame 1 at t=10 — on the next 10s boundary → INTERVAL
        assertThat(result.get(1).getTimestampSeconds()).isCloseTo(10.0, within(1e-6));
        assertThat(result.get(1).getSamplingReason()).isEqualTo(SamplingReason.INTERVAL);

        // Frame 2 at t=13 — off-boundary → SCENE_CHANGE
        assertThat(result.get(2).getTimestampSeconds()).isCloseTo(13.0, within(1e-6));
        assertThat(result.get(2).getSamplingReason()).isEqualTo(SamplingReason.SCENE_CHANGE);

        // Frame paths are inside the sibling dir.
        Path expectedDir = svc.framesDirFor(videoFile);
        assertThat(result).allSatisfy(f -> assertThat(Path.of(f.getFilePath())).startsWith(expectedDir));

        // Prior rows were wiped before persistence (idempotent re-runs).
        verify(repo, times(1)).deleteByVideo_Id(video.getId());
        verify(repo, times(1)).flush();
        verify(repo, times(1)).saveAll(any());
    }

    @Test
    void sampleFramesEnforcesMaxFrameCapAndDeletesExcessJpgs(@TempDir Path tmp) throws Exception {
        config.setMaxFramesPerVideo(2);  // cap below what ffmpeg "produced"

        Path videoFile = tmp.resolve("v.mp4");
        Files.writeString(videoFile, "fake mp4");

        FrameSamplingService svc = new FrameSamplingService(config, repo, TransactionOperations.withoutTransaction()) {
            @Override
            protected String runFfmpeg(Path inputVideo, Path framesDir) {
                writeFakeJpg(framesDir.resolve("0001.jpg"));
                writeFakeJpg(framesDir.resolve("0002.jpg"));
                writeFakeJpg(framesDir.resolve("0003.jpg"));
                writeFakeJpg(framesDir.resolve("0004.jpg"));
                return String.join("\n",
                        "[Parsed_showinfo_1 @ 0xff] n:0 pts:0 pts_time:0.0",
                        "[Parsed_showinfo_1 @ 0xff] n:1 pts:0 pts_time:10.0",
                        "[Parsed_showinfo_1 @ 0xff] n:2 pts:0 pts_time:20.0",
                        "[Parsed_showinfo_1 @ 0xff] n:3 pts:0 pts_time:30.0"
                );
            }
        };

        Video video = video(videoFile.toString());
        List<VideoFrame> result = svc.sampleFrames(video);

        assertThat(result).hasSize(2);
        Path framesDir = svc.framesDirFor(videoFile);
        // Excess JPGs (0003.jpg, 0004.jpg) deleted from disk so row count matches reality.
        try (var stream = Files.list(framesDir)) {
            assertThat(stream).hasSize(2);
        }
    }

    @Test
    void sampleFramesWipesStaleJpgsFromPreviousRun(@TempDir Path tmp) throws Exception {
        Path videoFile = tmp.resolve("v.mp4");
        Files.writeString(videoFile, "fake mp4");

        FrameSamplingService svc = new FrameSamplingService(config, repo, TransactionOperations.withoutTransaction()) {
            @Override
            protected String runFfmpeg(Path inputVideo, Path framesDir) {
                writeFakeJpg(framesDir.resolve("0001.jpg"));
                return "[Parsed_showinfo_1 @ 0xff] n:0 pts:0 pts_time:0.0";
            }
        };

        Path framesDir = svc.framesDirFor(videoFile);
        Files.createDirectories(framesDir);
        Files.writeString(framesDir.resolve("stale-0001.jpg"), "old");
        Files.writeString(framesDir.resolve("stale-0002.jpg"), "older");

        Video video = video(videoFile.toString());
        List<VideoFrame> result = svc.sampleFrames(video);

        assertThat(result).hasSize(1);
        try (var stream = Files.list(framesDir)) {
            assertThat(stream)
                    .extracting(p -> p.getFileName().toString())
                    .containsExactly("0001.jpg");
        }
    }

    private static Video video(String filePath) {
        Video v = new Video();
        v.setId(UUID.randomUUID());
        v.setFilePath(filePath);
        return v;
    }

    private static void writeFakeJpg(Path p) {
        try {
            // Tiny JPEG header byte to make Files.list happy; content doesn't matter for tests.
            Files.write(p, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
