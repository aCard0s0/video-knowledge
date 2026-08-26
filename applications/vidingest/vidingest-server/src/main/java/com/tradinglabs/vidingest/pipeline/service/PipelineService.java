package com.tradinglabs.vidingest.pipeline.service;

import com.tradinglabs.vidingest.api.pipeline.CreatePipelineRunResponse;
import com.tradinglabs.vidingest.pipeline.domain.PipelineErrorCode;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItem;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

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
    private final RunItemLeaseService runItemLeaseService;
    private final ExecutorService ingestionExecutor;

    /**
     * Caps how many run items execute phases at once. The executor itself stays unbounded
     * (virtual-thread-per-task) so shutdown still waits only for in-flight work rather than
     * draining a queue; the gate is what keeps 100 concurrent yt-dlp/ffmpeg/whisper jobs
     * from swamping the box and the connection pool.
     */
    private final Semaphore ingestionGate;

    /**
     * Run items executing phases right now, in this JVM. Read by {@link StuckItemReconciler},
     * which cannot otherwise tell a genuinely abandoned item from one sitting in a phase that
     * legitimately takes hours — {@code phase_updated_at} only moves on a phase *transition*.
     */
    private final Set<UUID> inFlightItemIds = ConcurrentHashMap.newKeySet();

    /**
     * Run items this JVM has taken responsibility for — queued behind the gate as well as
     * executing. Wider than {@link #inFlightItemIds} on purpose, and the two cannot be merged:
     * the heartbeat iterates the in-flight set, and a queued item holds no lease to renew.
     *
     * <p>This is what makes a {@code PENDING} item safe to reap. Such an item is either waiting on
     * our gate or was abandoned by a process that is gone, and only the owner can tell those
     * apart — {@code phase_updated_at} is stamped at creation for both.
     */
    private final Set<UUID> ownedItemIds = ConcurrentHashMap.newKeySet();

    public PipelineService(
            VideoRepository videoRepository,
            RunLifecycleService runLifecycle,
            RunItemLifecycleService runItemLifecycleService,
            RunAggregationService runAggregationService,
            PipelineRunItemRepository pipelineRunItemRepository,
            PipelinePhaseRegistry pipelinePhaseRegistry,
            PipelineErrorClassifier pipelineErrorClassifier,
            PipelineMetrics pipelineMetrics,
            RunItemLeaseService runItemLeaseService,
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
        this.runItemLeaseService = runItemLeaseService;
        this.ingestionExecutor = ingestionExecutor;
        this.ingestionGate = new Semaphore(ingestionConcurrency);
    }

    public record EnqueuedItem(String url, UUID itemId) {
    }

    public record BatchEnqueueResult(UUID runId, List<EnqueuedItem> items) {
    }

    public BatchEnqueueResult enqueuePipelineRunBatch(List<String> urls, Set<PipelineRunPhase> skipPhases) {
        if (urls == null || urls.isEmpty()) {
            throw new IllegalArgumentException("urls must not be empty");
        }

        String previewUrl = urls.get(0);
        PipelineRun run = runLifecycle.createPipelineRun(previewUrl, skipPhases);
        List<PipelineRunItem> items = runItemLifecycleService.createItems(run.getId(), urls);

        log.info("Pipeline run started: runId={}, items={}, urls={}, skipPhases={}",
                run.getId(), items.size(), urls.size(), skipPhases);

        for (var item : items) {
            enqueueItem(run.getId(), item.getId(), item.getUrl(), skipPhases);
        }
        pipelineMetrics.incrementCreated(items.size());
        pipelineMetrics.refreshInflightGauge();

        List<EnqueuedItem> enqueued = items.stream()
                .map(i -> new EnqueuedItem(i.getUrl(), i.getId()))
                .toList();
        return new BatchEnqueueResult(run.getId(), enqueued);
    }

    /**
     * @param requestedSkips the phases this attempt must skip, or {@code null} to reuse the run's
     *                       own set — see {@link #resolveSkips}.
     */
    public CreatePipelineRunResponse enqueueRetryBatch(UUID runId, Set<PipelineRunPhase> requestedSkips) {
        Set<PipelineRunPhase> skipPhases = resolveSkips(runId, requestedSkips);
        List<PipelineRunItem> items = runItemLifecycleService.listItems(runId);
        if (items.isEmpty()) {
            PipelineRun run = runLifecycle.prepareRetry(runId, skipPhases);
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
            enqueueItem(runId, item.getId(), url, skipPhases);
            return new CreatePipelineRunResponse(runId.toString(), List.of(new CreatePipelineRunResponse.ItemResult(
                    url,
                    CreatePipelineRunResponse.ItemStatus.ACCEPTED,
                    item.getId() != null ? item.getId().toString() : null,
                    null
            )));
        }
        // Run-level gate, separate from the per-item one below and answered before anything is
        // written. prepareRetry used to be what enforced this, but it validates and mutates in the
        // same call, so it cannot also be the thing we defer.
        if (runLifecycle.getPipelineRun(runId).getStatus() != RunStatus.FAILED) {
            throw new RunRetryNotAllowedException("Only FAILED pipeline runs can be retried");
        }

        // Decide first, mutate second. prepareRetry moves the run out of FAILED, and it used to
        // run before this partition — so a retry that accepted nothing still left the run PENDING,
        // and the next attempt was refused because only a FAILED run may be retried. That turned
        // "nothing to retry" into "this run can never be retried again".
        List<PipelineRunItem> retryable = items.stream()
                .filter(i -> i.getId() != null && retryRejection(i) == null)
                .toList();

        if (retryable.isEmpty()) {
            return new CreatePipelineRunResponse(runId.toString(), items.stream()
                    .map(i -> rejected(i, retryRejection(i)))
                    .toList());
        }

        runLifecycle.prepareRetry(runId, skipPhases);

        Set<UUID> accepted = retryable.stream().map(PipelineRunItem::getId).collect(Collectors.toSet());
        List<CreatePipelineRunResponse.ItemResult> results = items.stream()
                .map(i -> {
                    UUID itemId = i.getId();
                    if (itemId == null || !accepted.contains(itemId)) {
                        return rejected(i, retryRejection(i));
                    }
                    runItemLifecycleService.prepareRetry(itemId);
                    enqueueItem(runId, itemId, i.getUrl(), skipPhases);
                    return new CreatePipelineRunResponse.ItemResult(
                            i.getUrl(), CreatePipelineRunResponse.ItemStatus.ACCEPTED, itemId.toString(), null);
                })
                .toList();

        return new CreatePipelineRunResponse(runId.toString(), results);
    }

    /**
     * Why this item cannot be retried, or {@code null} when it can.
     *
     * <p>The old rule was {@code status == FAILED}, which left every other non-terminal state
     * unreachable. An item whose process died while it was still queued stays PENDING forever —
     * no reconciler swept PENDING, and retry refused it — so the URLs of a run interrupted by a
     * restart were simply lost, with the run stuck IN_PROGRESS and no error to show for it.
     *
     * <p>The question that actually matters is whether someone is running the item right now, and
     * the lease from PR #8 answers it across instances; {@code ownedItemIds} covers the window
     * before this JVM's own item has claimed one. Anything else non-terminal is fair game.
     */
    private String retryRejection(PipelineRunItem item) {
        if (item.getId() == null) {
            return "run item has no id";
        }
        if (item.getStatus() == RunStatus.COMPLETED) {
            return "run item already completed";
        }
        if (item.getStatus() == RunStatus.CANCELLED) {
            // Deliberate terminal state (duplicate video), not a failure to recover from.
            return "run item was cancelled";
        }
        if (ownedItemIds.contains(item.getId()) || RunItemLeaseService.isLive(item.getLeaseExpiresAt())) {
            return "run item is already running";
        }
        return null;
    }

    private static CreatePipelineRunResponse.ItemResult rejected(PipelineRunItem item, String reason) {
        return new CreatePipelineRunResponse.ItemResult(
                item.getUrl(),
                CreatePipelineRunResponse.ItemStatus.REJECTED,
                item.getId() != null ? item.getId().toString() : null,
                reason);
    }

    /**
     * @param requestedSkips the phases this attempt must skip, or {@code null} to reuse the run's
     *                       own set — see {@link #resolveSkips}.
     */
    public CreatePipelineRunResponse enqueueRetryItem(UUID runId, UUID itemId, Set<PipelineRunPhase> requestedSkips) {
        PipelineRun run = runLifecycle.getPipelineRun(runId);
        Set<PipelineRunPhase> skipPhases = requestedSkips != null ? requestedSkips : orEmpty(run.getSkipPhases());

        PipelineRunItem item = pipelineRunItemRepository.findByIdAndPipelineRun_Id(itemId, runId)
                .orElseThrow(() -> new RunItemNotFoundException(runId, itemId));

        if (run.getStatus() != RunStatus.FAILED) {
            throw new RunRetryNotAllowedException("Only FAILED pipeline runs can be retried");
        }

        String rejection = retryRejection(item);
        if (rejection != null) {
            return new CreatePipelineRunResponse(runId.toString(), List.of(rejected(item, rejection)));
        }

        runLifecycle.prepareRetry(runId, skipPhases);
        runItemLifecycleService.prepareRetry(itemId);
        enqueueItem(runId, itemId, item.getUrl(), skipPhases);
        return new CreatePipelineRunResponse(runId.toString(), List.of(
                new CreatePipelineRunResponse.ItemResult(
                        item.getUrl(), CreatePipelineRunResponse.ItemStatus.ACCEPTED, item.getId().toString(), null)
        ));
    }

    /**
     * The phases a retry must skip: the ones it asked for, or — when it asked for nothing at all —
     * the run's own set.
     *
     * <p>Absent and empty are different answers. Empty is an operator saying "run every enabled
     * phase"; absent is "retry this run the way it was configured", which is the only answer that
     * reproduces the run being retried. Collapsing the two is what made the runs board's retry
     * button — which has no phase picker and sent an empty list — silently re-enable the enrichment
     * phases a run had deliberately skipped, calling out to paddleocr and ollama and writing
     * artifacts for a run created without them.
     *
     * <p>The run is read only on the inherit path, so a caller that names its phases still pays for
     * no extra query.
     */
    private Set<PipelineRunPhase> resolveSkips(UUID runId, Set<PipelineRunPhase> requestedSkips) {
        return requestedSkips != null ? requestedSkips : orEmpty(runLifecycle.getPipelineRun(runId).getSkipPhases());
    }

    private static Set<PipelineRunPhase> orEmpty(Set<PipelineRunPhase> phases) {
        return phases != null ? phases : Set.of();
    }

    private void enqueueItem(UUID runId, UUID itemId, String videoUrl, Set<PipelineRunPhase> skipPhases) {
        // Claimed before the submit, not inside the task: between the two the item is PENDING with
        // nothing running, which is precisely what the reconciler now reaps.
        ownedItemIds.add(itemId);
        try {
            ingestionExecutor.submit(() -> {
                try {
                    runPipelineRunItem(runId, itemId, videoUrl, skipPhases);
                } catch (Throwable t) {
                    // runPipelineRunItem handles its own failures, but the recovery path can throw
                    // too (markFailed hits getReferenceById on a row that may be gone). The Future
                    // is discarded, so without this the cause never reaches the log and the item is
                    // left for StuckItemReconciler to reap an hour later with no explanation.
                    log.error("Pipeline run {} item {} died outside its own error handling", runId, itemId, t);
                } finally {
                    // Covers every exit, including the interrupt that returns before the gate.
                    ownedItemIds.remove(itemId);
                }
            });
        } catch (RuntimeException e) {
            // Rejected at submit (shutdown): the task will never run, so nothing else would drop
            // the claim, and the item would look live to the reconciler forever.
            ownedItemIds.remove(itemId);
            throw e;
        }
    }

    private void runPipelineRunItem(UUID runId, UUID itemId, String videoUrl, Set<PipelineRunPhase> skipPhases) {
        PipelinePhaseContext ctx = new PipelinePhaseContext(runId, itemId, videoUrl, skipPhases);
        try {
            ingestionGate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted waiting for an ingestion slot: runId={}, itemId={}", runId, itemId);
            return;
        }
        inFlightItemIds.add(itemId);
        // Claimed after the gate, so a queued item is never counted as running. Best-effort:
        // losing the lease write costs us reap protection, not correctness, and failing the
        // item here would be worse than proceeding without it.
        try {
            runItemLeaseService.acquire(itemId);
        } catch (Exception e) {
            log.warn("Could not acquire lease for item {}: {}", itemId, e.getMessage());
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
            inFlightItemIds.remove(itemId);
            try {
                runItemLeaseService.release(itemId);
            } catch (Exception e) {
                log.warn("Could not release lease for item {}: {}", itemId, e.getMessage());
            }
            ingestionGate.release();
            pipelineMetrics.refreshInflightGauge();
        }
    }

    /**
     * Whether this JVM has taken responsibility for {@code itemId} — executing it, or holding it
     * queued behind the gate. Narrower than the lease in one direction (it says nothing about
     * other instances) and wider in another (it covers items that have not started, which hold no
     * lease yet), so the reconciler and the retry path consult both.
     *
     * <p>Replaces {@code isItemInFlight}, which answered only for items past the gate and so had
     * nothing to say about the PENDING ones this now protects. The in-flight set is still what the
     * heartbeat renews — leases exist only past the gate.
     */
    public boolean isItemOwned(UUID itemId) {
        return ownedItemIds.contains(itemId);
    }

    /**
     * Keeps this instance's leases alive while it is executing items. Lives here because this is
     * where both halves already are — the in-flight set and the lease service. The interval must
     * stay well below {@code vidingest.lease.ttl}; the defaults leave a 5x margin, so several
     * consecutive misses are needed before live work looks abandoned.
     */
    @Scheduled(fixedDelayString = "${vidingest.lease.heartbeatMs:120000}",
            initialDelayString = "${vidingest.lease.heartbeatMs:120000}")
    public void renewLeases() {
        Set<UUID> inFlight = Set.copyOf(inFlightItemIds);
        if (inFlight.isEmpty()) {
            return;
        }
        try {
            int renewed = runItemLeaseService.renew(inFlight);
            if (renewed < inFlight.size()) {
                // Someone else owns an item we think we are running, or the row is gone. Worth
                // seeing: it is the shape a split brain or a premature reap would take.
                log.warn("Lease heartbeat renewed {} of {} in-flight items", renewed, inFlight.size());
            }
        } catch (Exception e) {
            // A heartbeat failure must not kill the scheduler thread; the next tick retries and
            // the TTL margin covers several misses.
            log.error("Lease heartbeat failed: {}", e.getMessage(), e);
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
