-- liquibase formatted sql
-- changeset vidingest:004-knowledge

-- The extraction scope: transcript and OCR fused into one per-window view, and the typed knowledge
-- the LLM draws out of it. Both are derived — wiped and repopulated per video by FUSE and
-- KNOWLEDGE — and neither is read without the other in play.

CREATE TABLE vidingest_multimodal_segments
(
    id              UUID PRIMARY KEY,
    video_id        UUID             NOT NULL REFERENCES vidingest_videos (id) ON DELETE CASCADE,
    segment_index   INT              NOT NULL,
    start_seconds   DOUBLE PRECISION NOT NULL,
    end_seconds     DOUBLE PRECISION NOT NULL,
    transcript_text TEXT,
    ocr_text        TEXT,
    -- Labels, not speaker ids, and no foreign key. DIARIZE is wipe-then-repopulate, so re-running
    -- it alone deletes every vidingest_speakers row and recreates it under a new uuid; an id array
    -- had nothing to catch that and served references to deleted rows. UNIQUE (video_id, label) on
    -- vidingest_speakers already makes the label the natural key and a re-run reproduces it, so the
    -- reference cannot dangle by construction.
    speaker_labels  TEXT[],
    created_at      TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (video_id, segment_index),
    CHECK (start_seconds <= end_seconds)
);

-- No index on video_id alone: leftmost column of both the unique constraint and the composite.
CREATE INDEX idx_vidingest_multimodal_video_time ON vidingest_multimodal_segments (video_id, start_seconds);

CREATE TABLE vidingest_knowledge_units
(
    id            UUID PRIMARY KEY,
    video_id      UUID         NOT NULL REFERENCES vidingest_videos (id) ON DELETE CASCADE,
    type          VARCHAR(32)  NOT NULL,
    title         VARCHAR(512),
    content       TEXT         NOT NULL,
    metadata      JSONB,
    start_seconds DOUBLE PRECISION,
    end_seconds   DOUBLE PRECISION,
    embedding     VECTOR(1536),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (start_seconds IS NULL OR end_seconds IS NULL OR start_seconds <= end_seconds)
);

-- No index on video_id alone (leftmost column of the composite) and none on metadata: nothing
-- queries JSONB by content.
CREATE INDEX idx_vidingest_knowledge_units_video_type ON vidingest_knowledge_units (video_id, type);
CREATE INDEX idx_vidingest_knowledge_units_embedding
    ON vidingest_knowledge_units
        USING hnsw (embedding vector_cosine_ops)
    WHERE embedding IS NOT NULL;

--rollback DROP TABLE IF EXISTS vidingest_knowledge_units CASCADE;
--rollback DROP TABLE IF EXISTS vidingest_multimodal_segments CASCADE;
