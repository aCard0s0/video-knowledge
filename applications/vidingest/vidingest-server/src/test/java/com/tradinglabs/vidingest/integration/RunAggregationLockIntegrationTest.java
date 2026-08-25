package com.tradinglabs.vidingest.integration;

import com.tradinglabs.vidingest.pipeline.domain.PipelineRun;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItem;
import com.tradinglabs.vidingest.pipeline.domain.PipelineRunPhase;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunItemRepository;
import com.tradinglabs.vidingest.pipeline.repo.PipelineRunRepository;
import com.tradinglabs.vidingest.pipeline.service.RunAggregationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code refreshRunState} reads a run's items and then writes the run — a read-then-write
 * across two tables that READ COMMITTED alone does not make safe. Two items finishing at once
 * could each read the item list and the slower one write its stale conclusion over the other's,
 * stranding the run at {@code IN_PROGRESS} with every item {@code COMPLETED}. Nothing re-runs
 * the aggregation after that, so the run stayed wrong forever.
 *
 * <p>These assert the mechanism rather than racing to reproduce the bug: the first proves the
 * row lock actually blocks a second caller, the second proves the outcome is right once it is
 * released.
 */
class RunAggregationLockIntegrationTest extends BaseVidingestIntegrationTest {

    @Autowired
    private PipelineRunItemRepository runItemRepository;

    @Autowired
    private RunAggregationService runAggregationService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void refreshRunStateBlocksWhileAnotherTransactionHoldsTheRunRow() throws Exception {
        UUID runId = seedRunWithCompletedItem();

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> transactionTemplate.execute(status -> {
                pipelineRunRepository.findWithLockById(runId).orElseThrow();
                lockHeld.countDown();
                awaitQuietly(releaseLock);
                return null;
            }));

            assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();

            Future<RunStatus> contender = pool.submit(() -> runAggregationService.refreshRunState(runId));

            // Blocked on the row lock. Without it this returns immediately, which is exactly
            // the window the lost update lived in.
            assertThatThrownBy(() -> contender.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseLock.countDown();
            holder.get(10, TimeUnit.SECONDS);

            assertThat(contender.get(10, TimeUnit.SECONDS)).isEqualTo(RunStatus.COMPLETED);
        } finally {
            releaseLock.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void refreshRunStatePromotesARunWhoseItemsHaveAllCompleted() {
        UUID runId = seedRunWithCompletedItem();

        assertThat(runAggregationService.refreshRunState(runId)).isEqualTo(RunStatus.COMPLETED);

        PipelineRun reloaded = pipelineRunRepository.findById(runId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(reloaded.getPhase()).isEqualTo(PipelineRunPhase.DONE);
    }

    /** A run left IN_PROGRESS whose only item already reached COMPLETED — the stranded shape. */
    private UUID seedRunWithCompletedItem() {
        PipelineRun run = pipelineRunRepository.saveAndFlush(PipelineRun.builder()
                .status(RunStatus.IN_PROGRESS)
                .videoUrl("https://example.com/video")
                .phase(PipelineRunPhase.CONTEXT)
                .build());

        runItemRepository.saveAndFlush(PipelineRunItem.builder()
                .pipelineRun(run)
                .url("https://example.com/video")
                .status(RunStatus.COMPLETED)
                .phase(PipelineRunPhase.DONE)
                .build());

        return run.getId();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
