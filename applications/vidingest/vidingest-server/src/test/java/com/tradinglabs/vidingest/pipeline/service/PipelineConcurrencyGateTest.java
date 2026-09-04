package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.core.download.service.VideoDownloadService;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItem;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemRepository;
import com.tradinglabs.vidingest.pipeline.service.phase.MetadataPhase;
import com.tradinglabs.vidingest.pipeline.service.phase.PipelinePhaseRegistry;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Pins the ingestion concurrency gate: {@code vidingest.ingestion.concurrency} caps how many
 * run items execute phases at once, so a 100-URL batch cannot fan out into 100 concurrent
 * yt-dlp/ffmpeg/whisper jobs against a 10-connection pool.
 *
 * <p>{@code PipelinePhase} is sealed, so this uses a real {@link MetadataPhase} with a mocked
 * {@code VideoDownloadService} that parks inside {@code execute} — the same "real phase impl,
 * mocked service" approach {@code VideoPhaseRunnerServiceTest} uses.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PipelineConcurrencyGateTest {

    private static final int PERMITS = 2;

    @Mock
    private RunItemLeaseService runItemLeaseService;
    private static final int ITEMS = 4;

    @Mock
    private VideoRepository videoRepository;
    @Mock
    private RunLifecycleService runLifecycle;
    @Mock
    private RunItemLifecycleService runItemLifecycleService;
    @Mock
    private RunAggregationService runAggregationService;
    @Mock
    private PipelineRunItemRepository pipelineRunItemRepository;
    @Mock
    private PipelinePhaseRegistry pipelinePhaseRegistry;
    @Mock
    private PipelineErrorClassifier pipelineErrorClassifier;
    @Mock
    private PipelineMetrics pipelineMetrics;
    @Mock
    private VideoDownloadService videoDownloadService;

    private ExecutorService executor;

    /** Released only once we've proven the gate held; keeps the parked phases parked. */
    private final CountDownLatch release = new CountDownLatch(1);
    /** Reaches zero once the permits are handed out — lets us assert without polling blindly. */
    private final CountDownLatch firstBatchAdmitted = new CountDownLatch(PERMITS);
    /** Must NOT reach zero: that is the proof the remaining items are still queued. */
    private final CountDownLatch allAdmitted = new CountDownLatch(ITEMS);
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger peakInFlight = new AtomicInteger();

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        // Open both lease-service gates: a mock's default false would reject the claim at submit
        // and skip execution at the lease, and nothing would ever reach the semaphore under test.
        when(runItemLeaseService.claim(any())).thenReturn(true);
        when(runItemLeaseService.acquire(any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        executor.shutdownNow();
    }

    @Test
    void gateCapsHowManyItemsExecutePhasesAtOnce() throws Exception {
        UUID runId = UUID.randomUUID();
        List<String> urls = new ArrayList<>();
        List<PipelineRunItem> items = new ArrayList<>();
        for (int i = 0; i < ITEMS; i++) {
            String url = "https://www.youtube.com/watch?v=vid" + i;
            urls.add(url);
            items.add(item(url));
        }

        when(runLifecycle.createPipelineRun(anyString(), any())).thenReturn(run(runId));
        when(runItemLifecycleService.createItems(any(), anyList())).thenReturn(items);
        when(pipelinePhaseRegistry.phases())
                .thenReturn(List.of(new MetadataPhase(videoDownloadService, videoRepository)));
        when(videoRepository.existsBySourceAndSourceVideoId(anyString(), anyString())).thenReturn(false);

        // The phase parks here. Peak concurrency inside this block IS the gate's effect.
        when(videoDownloadService.extractMetadata(anyString())).thenAnswer(inv -> {
            peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            firstBatchAdmitted.countDown();
            allAdmitted.countDown();
            release.await(5, TimeUnit.SECONDS);
            inFlight.decrementAndGet();
            return Map.of("extractor", "youtube", "id", UUID.randomUUID().toString());
        });

        PipelineService service = new PipelineService(
                videoRepository, runLifecycle, runItemLifecycleService, runAggregationService,
                pipelineRunItemRepository, pipelinePhaseRegistry, pipelineErrorClassifier,
                pipelineMetrics, runItemLeaseService, executor, PERMITS);

        service.enqueuePipelineRunBatch(urls, Set.of());

        // The permits get handed out...
        assertThat(firstBatchAdmitted.await(5, TimeUnit.SECONDS))
                .as("expected %d items to be admitted", PERMITS)
                .isTrue();
        // ...and the rest stay queued. Ungated, all %d would already be inside extractMetadata,
        // so this latch would hit zero immediately rather than time out.
        assertThat(allAdmitted.await(300, TimeUnit.MILLISECONDS))
                .as("expected items beyond the %d permits to stay queued", PERMITS)
                .isFalse();
        assertThat(peakInFlight.get())
                .as("items executing phases concurrently")
                .isEqualTo(PERMITS);

        release.countDown();
    }

    private static PipelineRun run(UUID id) {
        return PipelineRun.builder().id(id).status(RunStatus.PENDING).build();
    }

    private static PipelineRunItem item(String url) {
        return PipelineRunItem.builder()
                .id(UUID.randomUUID())
                .url(url)
                .status(RunStatus.PENDING)
                .attempt(1)
                .build();
    }
}
