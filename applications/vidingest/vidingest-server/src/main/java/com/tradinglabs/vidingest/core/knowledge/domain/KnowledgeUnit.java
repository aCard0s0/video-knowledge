package com.tradinglabs.vidingest.core.knowledge.domain;

import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import com.tradinglabs.vidingest.videos.domain.Video;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One unit of LLM-extracted knowledge for a video. Typed via {@link KnowledgeUnitType}
 * (ENTITY / TOPIC / SUMMARY / CLAIM / QUESTION) and embedded into {@code vector(1536)}
 * for semantic search (M8).
 *
 * <p>Backed by {@code vidingest_knowledge_units} (Liquibase changeset
 * {@code 012-knowledge-units.sql}). Cascades on video delete via the FK.
 *
 * <p>{@code metadata} is a free-form JSONB blob — typically holds salience, source
 * MultimodalSegment indices, prompt version, and any extractor-specific extras (e.g.
 * {@code entity_type} for ENTITY units).
 */
@Entity
@Table(name = "vidingest_knowledge_units")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeUnit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_id", nullable = false)
    private Video video;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private KnowledgeUnitType type;

    @Column(length = 512)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "start_seconds")
    private Double startSeconds;

    @Column(name = "end_seconds")
    private Double endSeconds;

    /**
     * Content embedding — same 1536-d shape as {@code vidingest_context_chunks.embedding}
     * so we can share the {@code EmbeddingsClient} and reuse pgvector tooling. Nullable
     * because operators can run knowledge extraction with embedding disabled (offline LLM
     * but no embed model loaded).
     */
    @Column(columnDefinition = "vector(1536)")
    private float[] embedding;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KnowledgeUnit that = (KnowledgeUnit) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
