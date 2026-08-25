package com.tradinglabs.vidingest.pipeline.repo;

import com.tradinglabs.vidingest.pipeline.domain.PipelineRunItem;
import com.tradinglabs.vidingest.pipeline.domain.RunStatus;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PipelineRunItemRepository extends JpaRepository<PipelineRunItem, UUID> {

    List<PipelineRunItem> findByPipelineRun_Id(UUID pipelineRunId, Sort sort);

    Optional<PipelineRunItem> findByIdAndPipelineRun_Id(UUID itemId, UUID pipelineRunId);

    default List<PipelineRunItem> findByPipelineRunIdOrdered(UUID pipelineRunId) {
        return findByPipelineRun_Id(pipelineRunId, Sort.by(Sort.Direction.ASC, "createdAt"));
    }

    long countByStatusIn(Collection<RunStatus> statuses);

    /** Total run-items in a run — used for run-completion summary logging. */
    long countByPipelineRun_Id(UUID pipelineRunId);

    /** Run-items in a run with a given terminal status — used for run-completion summary logging. */
    long countByPipelineRun_IdAndStatus(UUID pipelineRunId, RunStatus status);

    List<PipelineRunItem> findByStatusAndPhaseUpdatedAtBefore(RunStatus status, LocalDateTime before);

    /** Claims (or re-claims) an item for {@code owner} until {@code expiresAt}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE PipelineRunItem i
               SET i.leaseOwner = :owner, i.leaseExpiresAt = :expiresAt
             WHERE i.id = :itemId
            """)
    int acquireLease(@Param("itemId") UUID itemId,
                     @Param("owner") String owner,
                     @Param("expiresAt") LocalDateTime expiresAt);

    /**
     * Pushes out the expiry of the leases this owner still holds. Scoped by owner so a heartbeat
     * can never extend a lease another instance has since taken over.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE PipelineRunItem i
               SET i.leaseExpiresAt = :expiresAt
             WHERE i.id IN :itemIds AND i.leaseOwner = :owner
            """)
    int renewLeases(@Param("itemIds") Collection<UUID> itemIds,
                    @Param("owner") String owner,
                    @Param("expiresAt") LocalDateTime expiresAt);

    /** Drops the lease once the item is no longer being executed. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE PipelineRunItem i
               SET i.leaseOwner = null, i.leaseExpiresAt = null
             WHERE i.id = :itemId
            """)
    int releaseLease(@Param("itemId") UUID itemId);

    /** Whether any item of this run is still held by a live lease, in this JVM or another. */
    @Query("""
            SELECT COUNT(i) > 0 FROM PipelineRunItem i
             WHERE i.pipelineRun.id = :runId
               AND i.leaseExpiresAt IS NOT NULL
               AND i.leaseExpiresAt > :now
            """)
    boolean existsLiveLeaseForRun(@Param("runId") UUID runId, @Param("now") LocalDateTime now);
}

