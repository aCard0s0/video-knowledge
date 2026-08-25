package com.tradinglabs.vidingest.search.repo;

import com.tradinglabs.vidingest.search.domain.ContextChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Repository for ContextChunk entities
 * Part of the context feature
 */
@Repository
public interface ContextChunkRepository extends JpaRepository<ContextChunk, UUID> {

    List<ContextChunk> findByVideoIdOrderByChunkIndexAsc(UUID videoId);

    /**
     * Bulk-delete every context chunk for a video so regeneration converges cleanly.
     *
     * <p>{@code @Transactional} sits on the repository method, matching the six sibling
     * bulk-deletes. {@code ContextChunkGenerationService} used to supply the transaction from
     * its own {@code @Transactional}, but that annotation had to go: it also spanned the
     * blocking embeddings call.
     */
    @Modifying
    @Transactional
    @Query("delete from ContextChunk c where c.video.id = :videoId")
    void deleteByVideoId(@Param("videoId") UUID videoId);

    @Query(value = "SELECT * FROM vidingest_context_chunks ORDER BY embedding <-> CAST(:queryEmbedding AS vector) LIMIT :limit",
           nativeQuery = true)
    List<ContextChunk> findSimilarChunks(@Param("queryEmbedding") String queryEmbedding, @Param("limit") int limit);

    @Query(value = """
            SELECT c.id AS chunkId,
                   c.video_id AS videoId,
                   c.chunk_index AS chunkIndex,
                   c.content AS content,
                   v.title AS videoTitle,
                   v.channel_name AS channelName,
                   v.file_path AS filePath
            FROM vidingest_context_chunks c
            JOIN vidingest_videos v ON v.id = c.video_id
            ORDER BY c.embedding <-> CAST(:queryEmbedding AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<SimilarChunkProjection> findSimilarChunkProjections(
            @Param("queryEmbedding") String queryEmbedding,
            @Param("limit") int limit);

    interface SimilarChunkProjection {
        UUID getChunkId();
        UUID getVideoId();
        Integer getChunkIndex();
        String getContent();
        String getVideoTitle();
        String getChannelName();
        String getFilePath();
    }
}
