-- liquibase formatted sql
-- changeset vidingest:001-core-pipeline

-- Video ingestion pipeline: runs, videos, transcriptions + segments, embedding context chunks,
-- and per-URL run items with their append-only event log. Timestamps are TIMESTAMPTZ throughout.

CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================================
-- Pipeline runs
-- ============================================================
CREATE TABLE vidingest_pipeline_runs
(
    id               UUID PRIMARY KEY,
    status           VARCHAR(50) NOT NULL,
    phase            VARCHAR(50),
    phase_updated_at TIMESTAMPTZ,
    error_code       VARCHAR(80),
    error            TEXT,
    video_url        TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_vidingest_pipeline_runs_status_created_at ON vidingest_pipeline_runs (status, created_at DESC);

-- ============================================================
-- Videos
-- ============================================================
CREATE TABLE vidingest_videos
(
    id               UUID PRIMARY KEY,
    pipeline_run_id  UUID         REFERENCES vidingest_pipeline_runs (id) ON DELETE SET NULL,
    source           VARCHAR(50)  NOT NULL,
    source_video_id  VARCHAR(255) NOT NULL,
    channel_name     VARCHAR(255),
    title            TEXT,
    description      TEXT,
    duration_seconds INT,
    published_at     TIMESTAMPTZ,
    downloaded_at    TIMESTAMPTZ,
    file_path        TEXT,
    metadata         JSONB,
    status           VARCHAR(50)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (source, source_video_id)
);

CREATE INDEX idx_vidingest_videos_source ON vidingest_videos (source);
CREATE INDEX idx_vidingest_videos_channel_name ON vidingest_videos (channel_name);
CREATE INDEX idx_vidingest_videos_status ON vidingest_videos (status);
CREATE INDEX idx_vidingest_videos_pipeline_run_id ON vidingest_videos (pipeline_run_id);
CREATE INDEX idx_vidingest_videos_metadata ON vidingest_videos USING GIN (metadata);

-- ============================================================
-- Transcriptions + segments
-- ============================================================
CREATE TABLE vidingest_transcriptions
(
    id         UUID PRIMARY KEY,
    video_id   UUID        NOT NULL REFERENCES vidingest_videos (id) ON DELETE CASCADE,
    language   VARCHAR(10),
    full_text  TEXT,
    provider   VARCHAR(50),
    status     VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_vidingest_transcriptions_video_id ON vidingest_transcriptions (video_id);
CREATE INDEX idx_vidingest_transcriptions_status ON vidingest_transcriptions (status);

CREATE TABLE vidingest_transcription_segments
(
    id               UUID PRIMARY KEY,
    transcription_id UUID        NOT NULL REFERENCES vidingest_transcriptions (id) ON DELETE CASCADE,
    start_seconds    FLOAT       NOT NULL,
    end_seconds      FLOAT       NOT NULL,
    text             TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (start_seconds <= end_seconds)
);

CREATE INDEX idx_vidingest_segments_transcription_id ON vidingest_transcription_segments (transcription_id);
CREATE INDEX idx_vidingest_segments_transcription_id_start ON vidingest_transcription_segments (transcription_id, start_seconds);
CREATE INDEX idx_vidingest_segments_time ON vidingest_transcription_segments (start_seconds, end_seconds);

-- ============================================================
-- Context chunks (embeddings) — HNSW ANN index (pgvector >= 0.5)
-- ============================================================
CREATE TABLE vidingest_context_chunks
(
    id          UUID PRIMARY KEY,
    video_id    UUID         NOT NULL REFERENCES vidingest_videos (id) ON DELETE CASCADE,
    chunk_index INT          NOT NULL,
    content     TEXT         NOT NULL,
    embedding   VECTOR(1536),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (video_id, chunk_index)
);

CREATE INDEX idx_vidingest_chunks_video_id ON vidingest_context_chunks (video_id);
CREATE INDEX idx_vidingest_chunks_embedding
    ON vidingest_context_chunks
        USING hnsw (embedding vector_cosine_ops)
    WHERE embedding IS NOT NULL;

-- ============================================================
-- Pipeline run items (one per URL) + append-only event log
-- ============================================================
CREATE TABLE vidingest_pipeline_run_items
(
    id               UUID PRIMARY KEY,
    pipeline_run_id  UUID        NOT NULL REFERENCES vidingest_pipeline_runs (id) ON DELETE CASCADE,
    url              TEXT        NOT NULL,
    status           VARCHAR(50) NOT NULL,
    phase            VARCHAR(50),
    phase_updated_at TIMESTAMPTZ,
    failed_phase     VARCHAR(50),
    attempt          INTEGER     NOT NULL DEFAULT 1,
    error_code       VARCHAR(80),
    error            TEXT,
    video_id         UUID        REFERENCES vidingest_videos (id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (pipeline_run_id, url)
);

CREATE INDEX idx_vidingest_pipeline_run_items_run_id_created_at
    ON vidingest_pipeline_run_items (pipeline_run_id, created_at DESC);
CREATE INDEX idx_vidingest_pipeline_run_items_status_created_at
    ON vidingest_pipeline_run_items (status, created_at DESC);
CREATE INDEX idx_vidingest_pipeline_run_items_video_id
    ON vidingest_pipeline_run_items (video_id);
-- Supports the stuck-item reconciler: WHERE status = 'IN_PROGRESS' AND phase_updated_at < ?
CREATE INDEX idx_vidingest_pipeline_run_items_status_phase_updated_at
    ON vidingest_pipeline_run_items (status, phase_updated_at);

CREATE TABLE vidingest_pipeline_run_item_events
(
    id              UUID PRIMARY KEY,
    run_item_id     UUID        NOT NULL REFERENCES vidingest_pipeline_run_items (id) ON DELETE CASCADE,
    pipeline_run_id UUID        NOT NULL REFERENCES vidingest_pipeline_runs (id) ON DELETE CASCADE,
    event_type      VARCHAR(64) NOT NULL,
    attempt         INTEGER     NOT NULL,
    phase           VARCHAR(50),
    previous_phase  VARCHAR(50),
    status          VARCHAR(50),
    error_code      VARCHAR(80),
    error_message   TEXT,
    video_id        UUID        REFERENCES vidingest_videos (id) ON DELETE SET NULL,
    metadata        JSONB,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_vidingest_run_item_events_item_occurred
    ON vidingest_pipeline_run_item_events (run_item_id, occurred_at ASC, id ASC);
CREATE INDEX idx_vidingest_run_item_events_run_occurred
    ON vidingest_pipeline_run_item_events (pipeline_run_id, occurred_at ASC);
CREATE INDEX idx_vidingest_run_item_events_type
    ON vidingest_pipeline_run_item_events (event_type);

--rollback DROP TABLE IF EXISTS vidingest_pipeline_run_item_events CASCADE;
--rollback DROP TABLE IF EXISTS vidingest_pipeline_run_items CASCADE;
--rollback DROP TABLE IF EXISTS vidingest_context_chunks CASCADE;
--rollback DROP TABLE IF EXISTS vidingest_transcription_segments CASCADE;
--rollback DROP TABLE IF EXISTS vidingest_transcriptions CASCADE;
--rollback DROP TABLE IF EXISTS vidingest_videos CASCADE;
--rollback DROP TABLE IF EXISTS vidingest_pipeline_runs CASCADE;
