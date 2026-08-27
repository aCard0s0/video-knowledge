-- liquibase formatted sql
-- changeset vidingest:006-youtube-channels

-- The discovery scope: tracked channels and the videos found on them. Independent of the ingestion
-- pipeline by design — a row here is a *candidate*, and nothing links it to vidingest_videos except
-- the (source, source_video_id) pair resolved at ingest time.

CREATE TABLE vidingest_youtube_channels
(
    id                   UUID PRIMARY KEY,
    channel_url          TEXT        NOT NULL,
    display_name         VARCHAR(255),
    status               VARCHAR(50) NOT NULL,
    metadata             JSONB,
    last_sync_attempt_at TIMESTAMPTZ,
    last_sync_success_at TIMESTAMPTZ,
    last_error           TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (channel_url)
);

CREATE INDEX idx_vidingest_youtube_channels_status ON vidingest_youtube_channels (status);
CREATE INDEX idx_vidingest_youtube_channels_last_sync_success_at ON vidingest_youtube_channels (last_sync_success_at DESC);

CREATE TABLE vidingest_youtube_channel_videos
(
    id               UUID PRIMARY KEY,
    channel_id       UUID         NOT NULL REFERENCES vidingest_youtube_channels (id) ON DELETE CASCADE,
    youtube_video_id VARCHAR(64)  NOT NULL,
    title            TEXT,
    published_at     TIMESTAMPTZ,
    watch_url        TEXT         NOT NULL,
    metadata         JSONB,
    first_seen_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (channel_id, youtube_video_id)
);

-- No index on channel_id alone (leftmost column of the unique constraint) and none on metadata:
-- nothing queries JSONB by content.
CREATE INDEX idx_vidingest_youtube_channel_videos_published_at ON vidingest_youtube_channel_videos (published_at DESC);
CREATE INDEX idx_vidingest_youtube_channel_videos_youtube_video_id ON vidingest_youtube_channel_videos (youtube_video_id);

--rollback DROP TABLE IF EXISTS vidingest_youtube_channel_videos CASCADE;
--rollback DROP TABLE IF EXISTS vidingest_youtube_channels CASCADE;
