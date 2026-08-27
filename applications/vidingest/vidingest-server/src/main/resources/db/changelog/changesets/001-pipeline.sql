-- liquibase formatted sql
-- changeset vidingest:001-pipeline

-- Run orchestration and the video identity the runs revolve around.
--
-- These four tables live together because they reference each other in both directions and cannot
-- be created in separate files: vidingest_videos points at the run that produced it, while
-- vidingest_pipeline_run_items and its event log point at both the run and the video. Splitting
-- them would mean creating a table before its target exists.
--
-- Timestamps are TIMESTAMPTZ throughout; the JPA entities are OffsetDateTime written at UTC, so
-- the stored instant does not depend on the JVM's zone.

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
    -- What the run was configured *not* to do. One comma-separated column (PhaseSetConverter),
    -- not a child table: at most seven names, always read and written whole, never queried by
    -- member. NULL and '' both mean "nothing skipped". Nothing else records this, and both retry
    -- endpoints fall back to it when a client omits the field — an empty list is an explicit
    -- "run everything", an absent one inherits the run's own set.
    skip_phases      TEXT,
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
    -- The run that *last* touched this video. Identity is UNIQUE (source, source_video_id), so
    -- re-ingesting a URL reuses the row and overwrites this. "What did run X produce?" is answered
    -- from vidingest_pipeline_run_items.video_id, which is written once per run.
    pipeline_run_id  UUID         REFERENCES vidingest_pipeline_runs (id) ON DELETE SET NULL,
    -- yt-dlp's own `extractor`, lowercased. Deliberately not an enum: the extractor set is several
    -- hundred platforms, and folding the unlisted ones into one constant would let two videos from
    -- different sites collide on the unique key below.
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

-- No index on `source`: it is the leftmost column of the unique constraint above. No GIN index on
-- `metadata`: nothing queries JSONB by content anywhere in the codebase (no @>, no ->>), and the
-- one that used to exist reached 1656 kB against three rows.
CREATE INDEX idx_vidingest_videos_channel_name ON vidingest_videos (channel_name);
CREATE INDEX idx_vidingest_videos_status ON vidingest_videos (status);
CREATE INDEX idx_vidingest_videos_pipeline_run_id ON vidingest_videos (pipeline_run_id);

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
    -- Ownership lease. phase_updated_at only moves on a phase *transition*, so a phase that
    -- legitimately runs for hours is indistinguishable from abandoned work by timestamp alone.
    -- PipelineService.isItemOwned answers for this JVM only; the lease is the answer every
    -- instance can read. An item is reaped only when neither claims it.
    lease_owner      VARCHAR(160),
    lease_expires_at TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (pipeline_run_id, url)
);

-- No index on the lease columns: the reconciler sweep leads with (status, phase_updated_at), the
-- run-level check leads with pipeline_run_id, and acquire/renew/release go by primary key.
CREATE INDEX idx_vidingest_pipeline_run_items_run_id_created_at
    ON vidingest_pipeline_run_items (pipeline_run_id, created_at DESC);
CREATE INDEX idx_vidingest_pipeline_run_items_status_created_at
    ON vidingest_pipeline_run_items (status, created_at DESC);
CREATE INDEX idx_vidingest_pipeline_run_items_video_id
    ON vidingest_pipeline_run_items (video_id);
-- Supports the stuck-item reconciler: WHERE status IN (...) AND phase_updated_at < ?
CREATE INDEX idx_vidingest_pipeline_run_items_status_phase_updated_at
    ON vidingest_pipeline_run_items (status, phase_updated_at);

CREATE TABLE vidingest_pipeline_run_item_events
(
    id              UUID PRIMARY KEY,
    run_item_id     UUID        NOT NULL REFERENCES vidingest_pipeline_run_items (id) ON DELETE CASCADE,
    -- Denormalised from run_item_id on purpose: the run-level audit feed is the hottest read on
    -- this table and joining through the item to reach it is not worth the cost.
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
--rollback DROP TABLE IF EXISTS vidingest_videos CASCADE;
--rollback DROP TABLE IF EXISTS vidingest_pipeline_runs CASCADE;
