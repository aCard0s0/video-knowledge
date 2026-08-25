-- liquibase formatted sql
-- changeset vidingest:003-knowledge-extraction

-- Multimodal knowledge extraction: speakers (diarization), sampled frames + OCR, fused multimodal
-- segments, and typed knowledge units with HNSW vector search.

-- ============================================================
-- Speakers (diarization) + segment speaker link
-- ============================================================
CREATE TABLE vidingest_speakers
(
    id                   UUID PRIMARY KEY,
    video_id             UUID        NOT NULL REFERENCES vidingest_videos (id) ON DELETE CASCADE,
    label                VARCHAR(64) NOT NULL,
    display_name         VARCHAR(255),
    embedding_voiceprint VECTOR(192),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (video_id, label)
);

CREATE INDEX idx_vidingest_speakers_video_id ON vidingest_speakers (video_id);

-- Optional speaker link on transcript segments (nullable; deleting a speaker keeps the transcript).
ALTER TABLE vidingest_transcription_segments
    ADD COLUMN speaker_id UUID REFERENCES vidingest_speakers (id) ON DELETE SET NULL;

CREATE INDEX idx_vidingest_segments_speaker ON vidingest_transcription_segments (speaker_id);

-- ============================================================
-- Sampled frames + OCR
-- ============================================================
CREATE TABLE vidingest_video_frames
(
    id                UUID PRIMARY KEY,
    video_id          UUID             NOT NULL REFERENCES vidingest_videos (id) ON DELETE CASCADE,
    frame_index       INT              NOT NULL,
    timestamp_seconds DOUBLE PRECISION NOT NULL,
    file_path         TEXT             NOT NULL,
    sampling_reason   VARCHAR(32)      NOT NULL,
    width             INT,
    height            INT,
    created_at        TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (video_id, frame_index),
    CHECK (timestamp_seconds >= 0)
);

CREATE INDEX idx_vidingest_video_frames_video_id ON vidingest_video_frames (video_id);
CREATE INDEX idx_vidingest_video_frames_video_time ON vidingest_video_frames (video_id, timestamp_seconds);

CREATE TABLE vidingest_ocr_results
(
    id         UUID PRIMARY KEY,
    frame_id   UUID        NOT NULL REFERENCES vidingest_video_frames (id) ON DELETE CASCADE,
    text       TEXT        NOT NULL,
    confidence REAL,
    bbox       JSONB,
    language   VARCHAR(10),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_vidingest_ocr_results_frame_id ON vidingest_ocr_results (frame_id);

-- ============================================================
-- Fused multimodal segments
-- ============================================================
CREATE TABLE vidingest_multimodal_segments
(
    id              UUID PRIMARY KEY,
    video_id        UUID             NOT NULL REFERENCES vidingest_videos (id) ON DELETE CASCADE,
    segment_index   INT              NOT NULL,
    start_seconds   DOUBLE PRECISION NOT NULL,
    end_seconds     DOUBLE PRECISION NOT NULL,
    transcript_text TEXT,
    ocr_text        TEXT,
    speaker_ids     UUID[],
    created_at      TIMESTAMPTZ      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (video_id, segment_index),
    CHECK (start_seconds <= end_seconds)
);

CREATE INDEX idx_vidingest_multimodal_video_id ON vidingest_multimodal_segments (video_id);
CREATE INDEX idx_vidingest_multimodal_video_time ON vidingest_multimodal_segments (video_id, start_seconds);

-- ============================================================
-- Knowledge units (typed, embedded) — HNSW ANN index (pgvector >= 0.5)
-- ============================================================
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

CREATE INDEX idx_vidingest_knowledge_units_video_id ON vidingest_knowledge_units (video_id);
CREATE INDEX idx_vidingest_knowledge_units_video_type ON vidingest_knowledge_units (video_id, type);
CREATE INDEX idx_vidingest_knowledge_units_embedding
    ON vidingest_knowledge_units
        USING hnsw (embedding vector_cosine_ops)
    WHERE embedding IS NOT NULL;
CREATE INDEX idx_vidingest_knowledge_units_metadata ON vidingest_knowledge_units USING GIN (metadata);

--rollback DROP TABLE IF EXISTS vidingest_knowledge_units CASCADE;
--rollback DROP TABLE IF EXISTS vidingest_multimodal_segments CASCADE;
--rollback DROP TABLE IF EXISTS vidingest_ocr_results CASCADE;
--rollback DROP TABLE IF EXISTS vidingest_video_frames CASCADE;
--rollback ALTER TABLE vidingest_transcription_segments DROP COLUMN IF EXISTS speaker_id;
--rollback DROP TABLE IF EXISTS vidingest_speakers CASCADE;
