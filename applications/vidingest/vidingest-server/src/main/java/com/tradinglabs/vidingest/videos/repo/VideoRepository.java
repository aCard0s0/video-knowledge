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

    List<Video> findAllByPipelineRun_Id(UUID pipelineRunId);

    List<Video> findByPipelineRun_IdIn(Collection<UUID> pipelineRunIds);

    /**
     * Projection-backed run-list preview/count: selects only the columns needed for the pipeline
     * run-list card, avoiding hydration of full {@code Video} entities (including JSONB metadata) (#266).
     */
    @Query("""
            SELECT new com.tradinglabs.vidingest.videos.repo.RunVideoPreview(
                v.pipelineRun.id, v.id, v.channelName, v.title, v.status, v.createdAt)
            FROM Video v
            WHERE v.pipelineRun.id IN :runIds
            """)
    List<RunVideoPreview> findRunVideoPreviews(@Param("runIds") Collection<UUID> runIds);

    boolean existsBySourceAndSourceVideoId(String source, String sourceVideoId);
}
