-- liquibase formatted sql
-- changeset vidingest:007-run-created-at-index

-- `GET /pipelines?createdAfter=` filters on created_at with no status equality beside it — the
-- console's default listing asks for every status since local midnight.
--
-- This is *not* the redundancy the schema was cleaned of in 001: created_at is not the leftmost
-- column of idx_vidingest_pipeline_runs_status_created_at (status, created_at DESC), so a range
-- scan without an equality on status cannot use that index as anything better than a full scan.
-- DESC matches the only ordering the endpoint offers, so one index serves the filter and the sort.
CREATE INDEX idx_vidingest_pipeline_runs_created_at
    ON vidingest_pipeline_runs (created_at DESC);
