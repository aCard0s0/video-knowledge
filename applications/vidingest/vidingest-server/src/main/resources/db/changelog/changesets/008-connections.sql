-- liquibase formatted sql
-- changeset vidingest:008-connections

-- Runtime-editable connection settings for the five external runtimes: the embeddings and chat
-- LLMs and the transcription/diarization/OCR services. Everything here previously existed only as
-- @ConfigurationProperties bound from the environment at startup, so repointing the app at a
-- different host meant editing .env and recreating the container.
--
-- A row is an *override*. Absent, the environment value applies; present, it wins and is applied
-- to the live config bean at startup and on every write. That is why there is no NOT NULL on
-- provider or model: a connection may override only its base URL and inherit the rest.

CREATE TABLE vidingest_connections
(
    -- The ConnectionName enum, not a surrogate id. The set is closed and named (five values), so a
    -- UUID would add a join key nothing joins on and let two rows claim the same connection.
    name       VARCHAR(64) PRIMARY KEY,
    provider   VARCHAR(64),
    base_url   VARCHAR(2000) NOT NULL,
    model      VARCHAR(255),
    -- Stored as given. The API never returns it (ConnectionSummary carries hasApiKey instead), but
    -- at rest it is plaintext: this database is the operator's own, on their own host. Encrypt if
    -- that stops being true.
    api_key    TEXT,
    enabled    BOOLEAN       NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- No indexes and no created_at on purpose: at most five rows, always read whole at startup and
-- looked up by primary key otherwise. Anything else here would be write cost with no reader.

--rollback DROP TABLE IF EXISTS vidingest_connections CASCADE;
