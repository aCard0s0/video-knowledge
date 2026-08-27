-- liquibase formatted sql
-- changeset vidingest:003-frames-ocr

-- The visual scope: sampled frames and the text read off them. One OCR row per detected line,
-- so this is the highest-cardinality pair in the schema.

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

-- No index on video_id alone: leftmost column of both the unique constraint and the composite.
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

--rollback DROP TABLE IF EXISTS vidingest_ocr_results CASCADE;
--rollback DROP TABLE IF EXISTS vidingest_video_frames CASCADE;
