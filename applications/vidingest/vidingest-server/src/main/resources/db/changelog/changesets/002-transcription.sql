-- liquibase formatted sql
-- changeset vidingest:002-transcription

-- The audio scope: who spoke, and what was said.
--
-- Speakers sit here rather than with the visual tables because vidingest_transcription_segments
-- carries the FK to them — keeping the two apart is what forced an ALTER TABLE in the incremental
-- history this file replaces.

CREATE TABLE vidingest_speakers
(
    id                   UUID PRIMARY KEY,
    video_id             UUID        NOT NULL REFERENCES vidingest_videos (id) ON DELETE CASCADE,
    -- pyannote's own label ('SPEAKER_00', ...). Stable across a DIARIZE re-run, which is why
    -- vidingest_multimodal_segments references speakers by label rather than by id.
    label                VARCHAR(64) NOT NULL,
    display_name         VARCHAR(255),
    embedding_voiceprint VECTOR(192),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (video_id, label)
);

-- No index on video_id alone: leftmost column of the unique constraint above.

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
    -- Nullable, and ON DELETE SET NULL: DIARIZE wipes and repopulates speakers for a video, and
    -- losing the label must not take the transcript with it.
    speaker_id       UUID        REFERENCES vidingest_speakers (id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (start_seconds <= end_seconds)
);

-- No index on transcription_id alone: leftmost column of the composite below.
CREATE INDEX idx_vidingest_segments_transcription_id_start ON vidingest_transcription_segments (transcription_id, start_seconds);
CREATE INDEX idx_vidingest_segments_time ON vidingest_transcription_segments (start_seconds, end_seconds);
CREATE INDEX idx_vidingest_segments_speaker ON vidingest_transcription_segments (speaker_id);

--rollback DROP TABLE IF EXISTS vidingest_transcription_segments CASCADE;
--rollback DROP TABLE IF EXISTS vidingest_transcriptions CASCADE;
--rollback DROP TABLE IF EXISTS vidingest_speakers CASCADE;
