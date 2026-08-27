package com.tradinglabs.vidingest.integration;

import com.tradinglabs.vidingest.core.fusion.repo.MultimodalSegmentRepository;
import com.tradinglabs.vidingest.pipeline.service.PipelineService;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunRepository;
import com.tradinglabs.vidingest.search.repo.ContextChunkRepository;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import com.tradinglabs.vidingest.core.transcription.repo.TranscriptionRepository;
import com.tradinglabs.vidingest.core.transcription.repo.TranscriptionSegmentRepository;
import com.tradinglabs.vidingest.youtube.repo.YoutubeChannelRepository;
import com.tradinglabs.vidingest.youtube.repo.YoutubeChannelVideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.awaitility.Awaitility.await;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.shell.interactive.enabled=false",
                "spring.shell.noninteractive.enabled=false",
                "vidingest.youtube.sync.enabled=false",
                "vidingest.search.semantic-enabled=false",
                "vidingest.search.embeddings.provider=disabled"
        }
)
public abstract class BaseVidingestIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("vidingest_test")
            .withUsername("vidingest")
            .withPassword("vidingest");

    private static final Path VIDEO_STORAGE_ROOT = createTempVideoRoot();

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("vidingest.storage.video-path", () -> VIDEO_STORAGE_ROOT.toString());
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected MultimodalSegmentRepository multimodalSegmentRepository;

    @Autowired
    protected ContextChunkRepository contextChunkRepository;

    @Autowired
    protected TranscriptionSegmentRepository transcriptionSegmentRepository;

    @Autowired
    protected TranscriptionRepository transcriptionRepository;

    @Autowired
    protected VideoRepository videoRepository;

    @Autowired
    protected PipelineRunRepository pipelineRunRepository;

    @Autowired
    protected YoutubeChannelVideoRepository youtubeChannelVideoRepository;

    @Autowired
    protected YoutubeChannelRepository youtubeChannelRepository;

    @Autowired
    protected PipelineService pipelineService;

    /**
     * Waits for ingestion to go quiet, then wipes.
     *
     * A test that submits a run leaves virtual threads running past its own method — and awaiting
     * a terminal run status is not enough, because `runPipelineRunItem` releases its lease in a
     * `finally` that fires *after* `refreshRunState` has made the run COMPLETED. So the wipe raced
     * live writes to `vidingest_pipeline_run_items`: the delete cascaded into rows the pipeline's
     * own transaction was updating, the two took them in opposite orders, and Postgres killed one
     * side with `deadlock detected`. Intermittent, and it failed the whole suite when it hit.
     *
     * Bounded rather than indefinite: work that never drains is a bug worth failing on, not
     * something to hang the build for.
     */
    @BeforeEach
    void cleanupDatabase() {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(25))
                .until(() -> !pipelineService.hasWorkInFlight());

        multimodalSegmentRepository.deleteAllInBatch();
        contextChunkRepository.deleteAllInBatch();
        transcriptionSegmentRepository.deleteAllInBatch();
        transcriptionRepository.deleteAllInBatch();
        videoRepository.deleteAllInBatch();
        youtubeChannelVideoRepository.deleteAllInBatch();
        youtubeChannelRepository.deleteAllInBatch();
        pipelineRunRepository.deleteAllInBatch();
    }

    private static Path createTempVideoRoot() {
        try {
            Path dir = Files.createTempDirectory("vidingest-it-videos-");
            dir.toFile().deleteOnExit();
            return dir;
        } catch (java.io.IOException e) {
            throw new UncheckedIOException("Failed to create temp video storage root for integration tests", e);
        }
    }
}

