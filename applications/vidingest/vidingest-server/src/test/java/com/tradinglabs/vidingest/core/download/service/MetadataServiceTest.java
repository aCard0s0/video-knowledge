package com.tradinglabs.vidingest.core.download.service;

import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.exceptions.DuplicateVideoException;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The one branch nothing else covers: {@code createVideoFromMetadata} translating the database's
 * unique-constraint violation into a typed {@link DuplicateVideoException}.
 *
 * <p>{@code MetadataServiceIntegrationTest} already drives {@code processMetadata} through create
 * and update against a real schema, so neither is repeated here. The translation is not reachable
 * that way without racing two writers on the same {@code (source, source_video_id)} pair.
 *
 * <p>It is load-bearing in three places: {@code PipelineErrorClassifier} maps the typed exception to
 * {@code PipelineErrorCode.DUPLICATE_VIDEO}, {@code VidingestApiExceptionHandler} has a dedicated
 * handler for it, and {@code PipelineService} catches it by type. Lose the translation and a
 * duplicate reports as {@code UNEXPECTED} with a 500 instead.
 */
@ExtendWith(MockitoExtension.class)
class MetadataServiceTest {

    private static final Map<String, Object> METADATA = Map.of(
            "extractor", "youtube",
            "id", "abc123",
            "title", "A video");

    @Mock
    private VideoRepository videoRepository;

    @InjectMocks
    private MetadataService metadataService;

    @Test
    void createVideoFromMetadataTranslatesConstraintViolationToDuplicate() {
        DataIntegrityViolationException cause =
                new DataIntegrityViolationException("duplicate key value violates unique constraint");
        when(videoRepository.saveAndFlush(any(Video.class))).thenThrow(cause);

        assertThatThrownBy(() -> metadataService.createVideoFromMetadata(METADATA, "/videos/abc123.mp4"))
                .isInstanceOf(DuplicateVideoException.class)
                .hasCause(cause)
                .satisfies(e -> {
                    DuplicateVideoException dup = (DuplicateVideoException) e;
                    // The pair travels on the exception, not just in its message: the API handler
                    // renders these as separate fields.
                    assertThat(dup.source()).isEqualTo("youtube");
                    assertThat(dup.sourceVideoId()).isEqualTo("abc123");
                });
    }

    @Test
    void createVideoFromMetadataDoesNotSwallowOtherFailures() {
        // Only the integrity violation means "already ingested". A broader catch would turn a dead
        // connection into a duplicate report and send the operator looking for a video that is not
        // there.
        RuntimeException unrelated = new IllegalStateException("connection pool exhausted");
        when(videoRepository.saveAndFlush(any(Video.class))).thenThrow(unrelated);

        assertThatThrownBy(() -> metadataService.createVideoFromMetadata(METADATA, "/videos/abc123.mp4"))
                .isSameAs(unrelated);
    }
}
