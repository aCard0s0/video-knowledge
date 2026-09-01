package com.tradinglabs.vidingest.core.knowledge.repo;

import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import com.tradinglabs.vidingest.core.knowledge.domain.KnowledgeUnit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link KnowledgeUnit}. Read paths exposed:
 * <ul>
 *   <li>{@code findByVideo_IdOrderByCreatedAtAsc} — full timeline-ordered list for the
 *       M8 {@code getKnowledgeUnits} MCP tool.</li>
 *   <li>{@code findByVideo_IdAndTypeOrderByCreatedAtAsc} — typed slice (e.g. all ENTITY
 *       rows for a video).</li>
 *   <li>{@code findSimilarKnowledgeProjections} — pgvector similarity search for the M8
 *       {@code /api/v1/knowledge/search} endpoint. Mirrors the projection pattern used
 *       by {@code ContextChunkRepository}.</li>
 *   <li>{@code deleteByVideo_Id} — bulk wipe for idempotent re-runs.</li>
 * </ul>
 */
@Repository
public interface KnowledgeUnitRepository extends JpaRepository<KnowledgeUnit, UUID> {

    List<KnowledgeUnit> findByVideo_IdOrderByCreatedAtAsc(UUID videoId);

    List<KnowledgeUnit> findByVideo_IdAndTypeOrderByCreatedAtAsc(UUID videoId, KnowledgeUnitType type);

    long countByVideo_Id(UUID videoId);

    /**
     * Read-side projection that <b>excludes the {@code embedding vector(1536)} column</b>.
     * Hibernate's {@code FloatPrimitiveArrayJavaType} reader trips the PostgreSQL JDBC
     * driver's pgvector array-delimiter lookup ("No results were returned by the query")
     * whenever an entity-shape SELECT materialises the column. The REST endpoint
     * {@code GET /api/v1/videos/{id}/knowledge} does not need the embedding bytes, so we
     * fetch via a native projection that omits the column entirely.
     */
    @Query(value = """
            SELECT CAST(k.id AS varchar)         AS id,
                   CAST(k.video_id AS varchar)   AS videoId,
                   k.type                        AS type,
                   k.title                       AS title,
                   k.content                     AS content,
                   CAST(k.metadata AS varchar)   AS metadataJson,
                   k.start_seconds               AS startSeconds,
                   k.end_seconds                 AS endSeconds,
                   CAST(k.created_at AS varchar) AS createdAt
            FROM vidingest_knowledge_units k
            WHERE k.video_id = :videoId
              AND (CAST(:type AS varchar) IS NULL OR k.type = CAST(:type AS varchar))
            ORDER BY k.created_at ASC
            """, nativeQuery = true)
    List<KnowledgeUnitView> findViewsByVideoId(
            @Param("videoId") UUID videoId,
            @Param("type") String type);

    /**
     * Spring Data interface-based projection — column aliases above match the getter
     * names. {@code metadataJson} is a raw JSONB text payload that the mapper parses
     * into {@code Map&lt;String,Object&gt;}.
     */
    interface KnowledgeUnitView {
        String getId();
        String getVideoId();
        String getType();
        String getTitle();
        String getContent();
        String getMetadataJson();
        Double getStartSeconds();
        Double getEndSeconds();
        String getCreatedAt();
    }

    /**
     * Bulk-delete all knowledge units for a video. Implemented as an explicit
     * {@code @Modifying} query rather than Spring's derived {@code deleteBy*} method —
     * the derived method does a SELECT-then-DELETE which materialises the
     * {@code embedding vector(1536)} column via Hibernate's {@code FloatPrimitiveArrayJavaType},
     * and the PostgreSQL JDBC driver's pgvector handling raises
     * {@code "No results were returned by the query"} on the array-delimiter lookup. A
     * bulk DELETE skips the read path entirely.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM KnowledgeUnit ku WHERE ku.video.id = :videoId")
    int deleteByVideo_Id(@Param("videoId") UUID videoId);

    /**
     * Videos whose knowledge units were extracted under an older prompt, newest first.
     *
     * <p>{@code metadata.prompt_version} was already written on every row and nothing ever read it,
     * which made a prompt upgrade unactionable: v2 to v3 changed what extraction <em>means</em>
     * (4.3 of 19 stated rules recovered, against 14.7), so rows from the old prompt are not
     * comparable with new ones — but finding them meant querying the database by hand.
     *
     * <p>A listing rather than a bulk re-extract: one video takes ~2.5 minutes of LLM time, so a
     * bulk endpoint would hold a request open for hours and need its own async machinery. The
     * existing per-video {@code POST /videos/{id}/knowledge/regenerate} already does the work, and
     * a caller can drive it from this list at whatever rate it likes.
     *
     * <p>{@code MIN(...)} rather than a per-row filter, because a video is stale if <em>any</em> of
     * its units is: the phase is wipe-then-repopulate, so mixed versions within one video means an
     * interrupted run, and re-running is the answer either way.
     *
     * <p><b>This is the first query in the schema that reads JSONB by content</b>, which is why
     * there is still no GIN index on {@code metadata} — the root CLAUDE.md rule is to add one
     * alongside the query that needs it, and this one does not: it is an operator action over a
     * table of thousands of rows, not a hot path. Add the index if it ever moves onto one.
     */
    @Query(value = """
            SELECT CAST(k.video_id AS varchar)                                  AS videoId,
                   v.title                                                      AS videoTitle,
                   v.channel_name                                               AS channelName,
                   COUNT(*)                                                     AS unitCount,
                   MIN((k.metadata ->> 'prompt_version')::int)                   AS minPromptVersion,
                   MAX((k.metadata ->> 'prompt_version')::int)                   AS maxPromptVersion,
                   MAX(CAST(k.created_at AS varchar))                            AS lastExtractedAt
            FROM vidingest_knowledge_units k
            JOIN vidingest_videos v ON v.id = k.video_id
            WHERE k.metadata ->> 'prompt_version' IS NOT NULL
            GROUP BY k.video_id, v.title, v.channel_name
            HAVING MIN((k.metadata ->> 'prompt_version')::int) < :currentVersion
            ORDER BY MAX(k.created_at) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<StalePromptVersionView> findVideosBelowPromptVersion(
            @Param("currentVersion") int currentVersion,
            @Param("limit") int limit);

    /** Aliases above match these getters; Spring synthesises the implementation. */
    interface StalePromptVersionView {
        String getVideoId();
        String getVideoTitle();
        String getChannelName();
        long getUnitCount();
        int getMinPromptVersion();
        int getMaxPromptVersion();
        String getLastExtractedAt();
    }

    /**
     * Returns the {@code limit} most-similar knowledge units to {@code queryEmbedding},
     * optionally filtered by {@code type}. Pass {@code type=null} to search across all
     * types. The {@code <->} pgvector L2 operator gives us the nearest neighbours; the
     * ivfflat index (created in changeset 012) makes this O(log N) for the typical case.
     */
    @Query(value = """
            SELECT k.id            AS knowledgeUnitId,
                   k.video_id      AS videoId,
                   k.type          AS type,
                   k.title         AS title,
                   k.content       AS content,
                   k.start_seconds AS startSeconds,
                   k.end_seconds   AS endSeconds,
                   v.title         AS videoTitle,
                   v.channel_name  AS channelName
            FROM vidingest_knowledge_units k
            JOIN vidingest_videos v ON v.id = k.video_id
            WHERE k.embedding IS NOT NULL
              AND (CAST(:type AS varchar) IS NULL OR k.type = CAST(:type AS varchar))
            ORDER BY k.embedding <-> CAST(:queryEmbedding AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<SimilarKnowledgeProjection> findSimilarKnowledgeProjections(
            @Param("queryEmbedding") String queryEmbedding,
            @Param("type") String type,
            @Param("limit") int limit);

    /**
     * Spring Data interface-based projection — column aliases on the native query map to
     * the getter names here. Letting Spring synthesise the impl keeps us out of the row
     * mapper boilerplate.
     */
    interface SimilarKnowledgeProjection {
        UUID getKnowledgeUnitId();

        UUID getVideoId();

        String getType();

        String getTitle();

        String getContent();

        Double getStartSeconds();

        Double getEndSeconds();

        String getVideoTitle();

        String getChannelName();
    }
}
