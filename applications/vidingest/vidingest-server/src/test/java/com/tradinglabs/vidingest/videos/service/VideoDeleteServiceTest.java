package com.tradinglabs.vidingest.videos.service;

import com.tradinglabs.vidingest.config.ProjectPathResolver;
import com.tradinglabs.vidingest.config.VideoStorageConfig;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoDeleteServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void deleteVideoRemovesEntirePerVideoFolderIncludingSidecars() throws Exception {
        Path videoRoot = tempDir.resolve("videos");
        Path videoDir = videoRoot.resolve("Channel").resolve("20260429.Title");
        Files.createDirectories(videoDir);

        Path videoFile = videoDir.resolve("20260429.Title.mp4");
        Path metadataFile = videoDir.resolve("20260429.Title.metadata.json");
        Path whisperJson = videoDir.resolve("20260429.Title.whisper.json");
        Path whisperTxt = videoDir.resolve("20260429.Title.whisper.txt");

        Files.writeString(videoFile, "video");
        Files.writeString(metadataFile, "{}");
        Files.writeString(whisperJson, "{}");
        Files.writeString(whisperTxt, "text");

        UUID id = UUID.randomUUID();
        Video video = Video.builder()
                .id(id)
                .source("youtube")
                .sourceVideoId("abc123")
                .filePath(videoFile.toString())
                .build();

        VideoRepository repo = mock(VideoRepository.class);
        when(repo.findById(id)).thenReturn(Optional.of(video));

        VideoStorageConfig config = new VideoStorageConfig();
        config.setVideoPath(videoRoot.toString());

        ProjectPathResolver resolver = mock(ProjectPathResolver.class);
        when(resolver.getVideoPath()).thenReturn(videoRoot.toString());

        VideoDeleteService service = new VideoDeleteService(repo, config, resolver, TransactionOperations.withoutTransaction());
        service.deleteVideo(id);

        assertThat(Files.exists(videoDir)).isFalse();
        assertThat(Files.exists(videoFile)).isFalse();
        assertThat(Files.exists(metadataFile)).isFalse();
        assertThat(Files.exists(whisperJson)).isFalse();
        assertThat(Files.exists(whisperTxt)).isFalse();

        verify(repo).delete(video);
    }

    @Test
    void deleteVideoAlsoRemovesFramesSubdir() throws Exception {
        Path videoRoot = tempDir.resolve("videos");
        Path videoDir = videoRoot.resolve("Channel").resolve("20260429.Title");
        Path framesDir = videoDir.resolve("frames");
        Files.createDirectories(framesDir);

        Path videoFile = videoDir.resolve("20260429.Title.mp4");
        Files.writeString(videoFile, "video");
        Files.writeString(framesDir.resolve("0001.jpg"), "frame1");
        Files.writeString(framesDir.resolve("0002.jpg"), "frame2");

        UUID id = UUID.randomUUID();
        Video video = Video.builder()
                .id(id)
                .source("youtube")
                .sourceVideoId("abc123")
                .filePath(videoFile.toString())
                .build();

        VideoRepository repo = mock(VideoRepository.class);
        when(repo.findById(id)).thenReturn(Optional.of(video));

        VideoStorageConfig config = new VideoStorageConfig();
        config.setVideoPath(videoRoot.toString());
        ProjectPathResolver resolver = mock(ProjectPathResolver.class);
        when(resolver.getVideoPath()).thenReturn(videoRoot.toString());

        VideoDeleteService service = new VideoDeleteService(repo, config, resolver, TransactionOperations.withoutTransaction());
        service.deleteVideo(id);

        assertThat(Files.exists(videoDir)).isFalse();
        assertThat(Files.exists(videoFile)).isFalse();
        assertThat(Files.exists(framesDir)).isFalse();
        verify(repo).delete(video);
    }

    /**
     * The ordering that matters. A recursive directory delete cannot roll back, so it must not run
     * before the row delete that might fail: doing so left a COMPLETED video row pointing at a
     * folder that no longer existed, with every artifact endpoint 404ing and no way to tell why.
     */
    @Test
    void keepsTheArtifactsWhenTheRowDeleteFails() throws Exception {
        Path videoRoot = tempDir.resolve("videos");
        Path videoDir = videoRoot.resolve("Channel").resolve("20260429.Title");
        Files.createDirectories(videoDir);
        Path videoFile = videoDir.resolve("20260429.Title.mp4");
        Files.writeString(videoFile, "video");

        UUID id = UUID.randomUUID();
        Video video = Video.builder()
                .id(id)
                .source("youtube")
                .sourceVideoId("abc123")
                .filePath(videoFile.toString())
                .build();

        VideoRepository repo = mock(VideoRepository.class);
        when(repo.findById(id)).thenReturn(Optional.of(video));
        doThrow(new DataIntegrityViolationException("cascade deadlock")).when(repo).delete(video);

        VideoStorageConfig config = new VideoStorageConfig();
        config.setVideoPath(videoRoot.toString());
        ProjectPathResolver resolver = mock(ProjectPathResolver.class);
        when(resolver.getVideoPath()).thenReturn(videoRoot.toString());

        VideoDeleteService service =
                new VideoDeleteService(repo, config, resolver, TransactionOperations.withoutTransaction());

        assertThatThrownBy(() -> service.deleteVideo(id))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(Files.exists(videoFile)).isTrue();
        assertThat(Files.exists(videoDir)).isTrue();
    }
}
