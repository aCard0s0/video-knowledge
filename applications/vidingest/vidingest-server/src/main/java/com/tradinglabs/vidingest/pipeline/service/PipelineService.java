package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.api.pipeline.CreatePipelineRunResponse;
import com.tradinglabs.vidingest.pipeline.domain.PipelineErrorCode;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItem;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import com.tradinglabs.vidingest.pipeline.exceptions.RunRetryNotAllowedException;
import com.tradinglabs.vidingest.pipeline.exceptions.RunItemNotFoundException;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemRepository;
import com.tradinglabs.vidingest.pipeline.service.phase.PipelinePhase;
import com.tradinglabs.vidingest.pipeline.service.phase.PipelinePhaseContext;
import com.tradinglabs.vidingest.pipeline.service.phase.PipelinePhaseRegistry;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import com.tradinglabs.vidingest.videos.exceptions.DuplicateVideoException;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

@Service
@Slf4j
public class PipelineService {

    private final VideoRepository videoRepository;
    private final RunLifecycleService runLifecycle;
    private final RunItemLifecycleService runItemLifecycleService;
    private final RunAggregationService runAggregationService;
    private final PipelineRunItemRepository pipelineRunItemRepository;
    private final PipelinePhaseRegistry pipelinePhaseRegistry;
    private final PipelineErrorClassifier pipelineErrorClassifier;
    private final PipelineMetrics pipelineMetrics;
    private final ExecutorService ingestionExecutor;

    /**
     * Caps how many run items execute phases at once. The executor itself stays unbounded
     * (virtual-thread-per-task) so shutdown still waits only for in-flight work rather than
     * draining a queue; the gate is what keeps 100 concurrent yt-dlp/ffmpeg/whisper jobs
     * from swamping the box and the connection pool.
     */
    private final Semaphore ingestionGate;

    public PipelineService(
            VideoRepository videoRepository,
            RunLifecycleService runLifecycle,
            RunItemLifecycleService runItemLifecycleService,
            RunAggregationService runAggregationService,
            PipelineRunItemRepository pipelineRunItemRepository,
            PipelinePhaseRegistry pipelinePhaseRegistry,
            PipelineErrorClassifier pipelineErrorClassifier,
            PipelineMetrics pipelineMetrics,
            @Qualifier("vidingestIngestionExecutor") ExecutorService ingestionExecutor,
            @Value("${vidingest.ingestion.concurrency:4}") int ingestionConcurrency
    ) {
        this.videoRepository = videoRepository;
        this.runLifecycle = runLifecycle;
        this.runItemLifecycleService = runItemLifecycleService;
        this.runAggregationService = runAggregationService;
        this.pipelineRunItemRepository = pipelineRunItemRepository;
        this.pipelinePhaseRegistry = pipelinePhaseRegistry;
        this.pipelineErrorClassifier = pipelineErrorClassifier;
        this.pipelineMetrics = pipelineMetrics;
        this.ingestionExecutor = ingestionExecutor;
        this.ingestionGate = new Semaphore(ingestionConcurrency);
    }

    public record EnqueuedItem(String url, UUID itemId) {
    }

    public record BatchEnqueueResult(UUID runId, List<EnqueuedItem> items) {
    }

    /**
     * Bundles the enrichment skip flags so signatures don't multiply as more phases land.
     */
    public record PipelineSkipFlags(
            boolean skipTranscription,
            boolean skipContext,
            boolean skipDiarize,
            boolean skipFrames,
            boolean skipOcr,
            boolean skipKnowledge
    ) {
        public static PipelineSkipFlags defaults() {
            return new PipelineSkipFlags(false, false, true, true, true, true);
        }
    }

    // --- Full-flag entry points (used by intake + retry from M1 onwards) ----------------------

    public BatchEnqueueResult enqueuePipelineRunBatch(List<String> urls, PipelineSkipFlags flags) {
        if (urls == null || urls.isEmpty()) {
            throw new IllegalArgumentException("urls must not be empty");
        }

        String previewUrl = urls.get(0);
        PipelineRun run = runLifecycle.createPipelineRun(previewUrl);
        List<PipelineRunItem> items = runItemLifecycleService.createItems(run.getId(), urls);

        log.info("Pipeline run started: runId={}, items={}, urls={}, skipFlags={}",
                run.getId(), items.size(), urls.size(), flags);

        for (var item : items) {
            enqueueItem(run.getId(), item.getId(), item.getUrl(), flags);
        }
        pipelineMetrics.incrementCreated(items.size());
        pipelineMetrics.refreshInflightGauge();

        List<EnqueuedItem> enqueued = items.stream()
                .map(i -> new EnqueuedItem(i.getUrl(), i.getId()))
                .toList();
        return new BatchEnqueueResult(run.getId(), enqueued);
    }

    public CreatePipelineRunResponse enqueueRetryBatch(UUID runId, PipelineSkipFlags flags) {
        List<PipelineRunItem> items = runItemLifecycleService.listItems(runId);
        if (items.isEmpty()) {
            PipelineRun run = runLifecycle.prepareRetry(runId);
            String url = run.getVideoUrl();
            if (url == null || url.isBlank()) {
                return new CreatePipelineRunResponse(runId.toString(), List.of(new CreatePipelineRunResponse.ItemResult(
                        null,
                        CreatePipelineRunResponse.ItemStatus.REJECTED,
                        null,
                        "no run items found and no run videoUrl stored for retry"
                )));
            }

            var created = runItemLifecycleService.createItems(runId, List.of(url));
            var item = created.getFirst();
            enqueueItem(runId, item.getId(), url, flags);
            return new CreatePipelineRunResponse(runId.toString(), List.of(new CreatePipelineRunResponse.ItemResult(
                    url,
                    CreatePipelineRunResponse.ItemStatus.ACCEPTED,
                    item.getId() != null ? item.getId().toString() : null,
                    null
            )));
        }
        runLifecycle.prepareRetry(runId);

        List<CreatePipelineRunResponse.ItemResult> results = items.stream()
                .map(i -> {
                    UUID itemId = i.getId();
                    String itemIdStr = itemId != null ? itemId.toString() : null;
                    if (i.getStatus() != RunStatus.FAILED) {
                        return new CreatePipelineRunResponse.ItemResult(i.getUrl(), CreatePipelineRunResponse.ItemStatus.REJECTED, itemIdStr, "run item is not FAILED");
                    }

                    if (itemId == null) {
                        return new CreatePipelineRunResponse.ItemResult(i.getUrl(), CreatePipelineRunResponse.ItemStatus.REJECTED, null, "run item has no id");
                    }

                    runItemLifecycleService.prepareRetry(itemId);
                    enqueueItem(runId, itemId, i.getUrl(), flags);
                    return new CreatePipelineRunResponse.ItemResult(i.getUrl(), CreatePipelineRunResponse.ItemStatus.ACCEPTED, itemIdStr, null);
                })
                .toList();

        return new CreatePipelineRunResponse(runId.toString(), results);
    }

    public CreatePipelineRunResponse enqueueRetryItem(UUID runId, UUID itemId, PipelineSkipFlags flags) {
        PipelineRun run = runLifecycle.getPipelineRun(runId);

        PipelineRunItem item = pipelineRunItemRepository.findByIdAndPipelineRun_Id(itemId, runId)
                .orElseThrow(() -> new RunItemNotFoundException(runId, itemId));

        if (run.getStatus() != RunStatus.FAILED) {
            throw new RunRetryNotAllowedException("Only FAILED pipeline runs can be retried");
        }

        String itemIdStr = item.getId() != null ? item.getId().toString() : null;
        if (item.getStatus() != RunStatus.FAILED) {
            return new CreatePipelineRunResponse(runId.toString(), List.of(
                    new CreatePipelineRunResponse.ItemResult(item.getUrl(), CreatePipelineRunResponse.ItemStatus.REJECTED, itemIdStr, "run item is not FAILED")
            ));
        }

        runLifecycle.prepareRetry(runId);
        runItemLifecycleService.prepareRetry(itemId);
        enqueueItem(runId, itemId, item.getUrl(), flags);
        return new CreatePipelineRunResponse(runId.toString(), List.of(
                new CreatePipelineRunResponse.ItemResult(item.getUrl(), CreatePipelineRunResponse.ItemStatus.ACCEPTED, itemIdStr, null)
        ));
    }

    private void enqueueItem(UUID runId, UUID itemId, String videoUrl, PipelineSkipFlags flags) {
        ingestionExecutor.submit(() -> {
            try {
                runPipelineRunItem(runId, itemId, videoUrl, flags);
            } catch (Throwable t) {
                // runPipelineRunItem handles its own failures, but the recovery path can throw
                // too (markFailed hits getReferenceById on a row that may be gone). The Future
                // is discarded, so without this the cause never reaches the log and the item is
                // left for StuckItemReconciler to reap an hour later with no explanation.
                log.error("Pipeline run {} item {} died outside its own error handling", runId, itemId, t);
            }
        });
    }

    private void runPipelineRunItem(UUID runId, UUID itemId, String videoUrl, PipelineSkipFlags flags) {
        PipelinePhaseContext ctx = new PipelinePhaseContext(
                runId,
                itemId,
                videoUrl,
                flags.skipTranscription(),
                flags.skipContext(),
                flags.skipDiarize(),
                flags.skipFrames(),
                flags.skipOcr(),
                flags.skipKnowledge()
        );
        try {
            ingestionGate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted waiting for an ingestion slot: runId={}, itemId={}", runId, itemId);
            return;
        }
        // Started after the gate so elapsedMs stays execution time, not queue wait.
        long itemStartNs = System.nanoTime();
        try {
            executePhases(ctx);
            finalizeSuccess(ctx);
            pipelineMetrics.incrementCompleted();
            long elapsedMs = (System.nanoTime() - itemStartNs) / 1_000_000;
            UUID videoId = ctx.getVideo() != null ? ctx.getVideo().getId() : null;
            log.info("Video pipeline COMPLETED: runId={}, itemId={}, videoId={}, elapsedMs={}",
                    runId, itemId, videoId, elapsedMs);
            // Refresh run-level aggregation: if this was the last in-progress item, the
            // run itself transitions to COMPLETED and the run-level log fires below.
            logRunCompletionIfFinished(runId);
        } catch (DuplicateVideoException e) {
            log.info("Pipeline run {} item {} cancelled due to duplicate video: {}", runId, itemId, e.getMessage());
            videoRepository.findBySourceAndSourceVideoId(e.source(), e.sourceVideoId())
                    .map(Video::getId)
                    .ifPresent(existingVideoId -> runItemLifecycleService.attachVideo(itemId, existingVideoId));
            runItemLifecycleService.markCancelled(itemId, PipelineErrorCode.DUPLICATE_VIDEO, e.getMessage());
            runAggregationService.refreshRunState(runId);
            pipelineMetrics.incrementCancelled(PipelineErrorCode.DUPLICATE_VIDEO);
        } catch (Exception e) {
            PipelineErrorCode code = pipelineErrorClassifier.classify(e);
            log.error("Pipeline run {} item {} failed", runId, itemId, e);
            runItemLifecycleService.markFailed(itemId, code, e.getMessage());
            runAggregationService.refreshRunState(runId);
            pipelineMetrics.incrementFailed(code);
        } finally {
            ingestionGate.release();
            pipelineMetrics.refreshInflightGauge();
        }
    }

    private void executePhases(PipelinePhaseContext ctx) throws Exception {
        boolean first = true;
        for (PipelinePhase phase : pipelinePhaseRegistry.phases()) {
            if (!phase.applies(ctx)) {
                continue;
            }
            if (first) {
                runItemLifecycleService.markInProgress(ctx.getItemId(), phase.phase());
                runAggregationService.ensureRunInProgress(ctx.getRunId(), phase.phase());
                log.info("Starting pipeline run {} item {} for URL: {}", ctx.getRunId(), ctx.getItemId(), ctx.getVideoUrl());
                first = false;
            } else {
                runItemLifecycleService.markPhase(ctx.getItemId(), phase.phase());
                runAggregationService.updateRunPhase(ctx.getRunId(), phase.phase());
            }
            UUID videoId = ctx.getVideo() != null ? ctx.getVideo().getId() : null;
            log.info("Phase START: runId={}, itemId={}, videoId={}, phase={}",
                    ctx.getRunId(), ctx.getItemId(), videoId, phase.phase());

            Timer.Sample sample = pipelineMetrics.startPhaseTimer();
            long phaseStartNs = System.nanoTime();
            boolean success = false;
            try {
                phase.execute(ctx);
                success = true;
            } finally {
                pipelineMetrics.stopPhaseTimer(sample, phase.phase(), success);
                long elapsedMs = (System.nanoTime() - phaseStartNs) / 1_000_000;
                UUID vid = ctx.getVideo() != null ? ctx.getVideo().getId() : null;
                if (success) {
                    // Audit event: pairs with the ITEM_PHASE_ENTERED event so the Item
                    // History view flips this phase from "In progress" → "Completed".
                    try {
                        runItemLifecycleService.recordPhaseCompleted(ctx.getItemId(), phase.phase());
                    } catch (Exception auditErr) {
                        log.warn("Failed to record ITEM_PHASE_COMPLETED audit event: runId={}, itemId={}, phase={}, err={}",
                                ctx.getRunId(), ctx.getItemId(), phase.phase(), auditErr.getMessage());
                    }
                    log.info("Phase COMPLETED: runId={}, itemId={}, videoId={}, phase={}, elapsedMs={}",
                            ctx.getRunId(), ctx.getItemId(), vid, phase.phase(), elapsedMs);
                } else {
                    log.warn("Phase FAILED: runId={}, itemId={}, videoId={}, phase={}, elapsedMs={}",
                            ctx.getRunId(), ctx.getItemId(), vid, phase.phase(), elapsedMs);
                }
            }
        }
    }

    /**
     * After an item completes, check whether the parent run has finished overall and
     * emit one info log per run when it does. The aggregation service is the source of
     * truth — it transitions the run to COMPLETED only when every item has reached a
     * terminal status (COMPLETED / FAILED / CANCELLED).
     */
    private void logRunCompletionIfFinished(UUID runId) {
        try {
            var run = runLifecycle.getPipelineRun(runId);
            if (run.getStatus() == RunStatus.COMPLETED
                    || run.getStatus() == RunStatus.FAILED
                    || run.getStatus() == RunStatus.CANCELLED) {
                long total = pipelineRunItemRepository.countByPipelineRun_Id(runId);
                long completed = pipelineRunItemRepository.countByPipelineRun_IdAndStatus(runId, RunStatus.COMPLETED);
                long failed = pipelineRunItemRepository.countByPipelineRun_IdAndStatus(runId, RunStatus.FAILED);
                long cancelled = pipelineRunItemRepository.countByPipelineRun_IdAndStatus(runId, RunStatus.CANCELLED);
                log.info("Pipeline run FINISHED: runId={}, status={}, items={}, completed={}, failed={}, cancelled={}",
                        runId, run.getStatus(), total, completed, failed, cancelled);
            }
        } catch (Exception e) {
            // Logging only — never let it impact pipeline flow.
            log.debug("Run-completion check failed (non-fatal): runId={}, err={}", runId, e.getMessage());
        }
    }

    private void finalizeSuccess(PipelinePhaseContext ctx) {
        Video video = ctx.getVideo();
        if (video != null) {
            video.setStatus(VideoStatus.COMPLETED);
            videoRepository.save(video);
        }
        runItemLifecycleService.markCompleted(ctx.getItemId());
        runAggregationService.refreshRunState(ctx.getRunId());
    }
}
