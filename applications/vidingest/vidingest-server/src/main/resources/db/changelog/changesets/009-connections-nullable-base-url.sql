-- liquibase formatted sql
-- changeset vidingest:009-connections-nullable-base-url

-- FRAME_SAMPLE joined vidingest_connections as a connection with no connection: it is local
-- ffmpeg, so its override row carries only `enabled`. Everything else on the table was already
-- nullable for the same reason — a row overrides what it names and inherits the rest — base_url
-- was NOT NULL only because until now every name had one.
--
-- The service still requires an absolute http(s) URL for every connection that is reached over
-- HTTP (ConnectionSettingsService.validateBaseUrl, gated on Binding.hasBaseUrl), so dropping the
-- constraint does not let a sidecar be saved without an endpoint.

ALTER TABLE vidingest_connections
    ALTER COLUMN base_url DROP NOT NULL;

--rollback ALTER TABLE vidingest_connections ALTER COLUMN base_url SET NOT NULL;
