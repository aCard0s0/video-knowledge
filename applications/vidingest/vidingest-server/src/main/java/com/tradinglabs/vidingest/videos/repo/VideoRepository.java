package com.tradinglabs.vidingest.videos.repo;

import com.tradinglabs.vidingest.videos.domain.Video;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VideoRepository extends JpaRepository<Video, UUID>, JpaSpecificationExecutor<Video> {

    Optional<Video> findBySourceAndSourceVideoId(String source, String sourceVideoId);

    List<Video> findBySourceAndSourceVideoIdIn(String source, Collection<String> sourceVideoIds);

    /**
     * Projection-backed run-list preview/count: selects only the columns needed for the pipeline
     * run-list card, avoiding hydration of full {@code Video} entities (including JSONB metadata) (#266).
     *
     * <p>Joined through {@code PipelineRunItem}, not {@code Video.pipelineRun}. Video identity is
     * {@code UNIQUE (source, source_video_id)}, so re-ingesting a URL reuses the row and
     * {@code VideoLifecycleService} overwrites {@code videos.pipeline_run_id} to the newest run —
     * which silently dropped that video from every older run's preview. The run item is written
     * once per run and never reassigned, so it is the only honest answer to "what did this run
     * produce". {@code videos.pipeline_run_id} still means "the run that last touched this video",
     * which is what {@code VideoSummaryMapper} exposes, so the column stays.
     */
    @Query("""
            SELECT new com.tradinglabs.vidingest.videos.repo.RunVideoPreview(
                i.pipelineRun.id, v.id, v.channelName, v.title, v.status, v.createdAt)
            FROM PipelineRunItem i, Video v
            WHERE v.id = i.videoId AND i.pipelineRun.id IN :runIds
            """)
    List<RunVideoPreview> findRunVideoPreviews(@Param("runIds") Collection<UUID> runIds);

    boolean existsBySourceAndSourceVideoId(String source, String sourceVideoId);
}
