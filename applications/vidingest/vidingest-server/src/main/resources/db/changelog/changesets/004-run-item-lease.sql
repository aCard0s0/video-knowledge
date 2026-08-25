-- liquibase formatted sql
-- changeset vidingest:004-run-item-lease

-- Ownership lease for in-flight run items.
--
-- The stuck-item reconciler previously asked PipelineService whether *this JVM* was running an
-- item, because phase_updated_at only moves on a phase transition and a long phase looks
-- identical to abandoned work. That answer is per-process: a second instance sees none of the
-- first instance's live work and reaps it. A lease moves the same question into the database,
-- where every instance can read it.
ALTER TABLE vidingest_pipeline_run_items
    ADD COLUMN lease_owner      VARCHAR(160),
    ADD COLUMN lease_expires_at TIMESTAMPTZ;

-- No index on the lease columns: every query that reads them is already served. The reconciler
-- sweep leads with (status, phase_updated_at), the run-level check leads with pipeline_run_id,
-- and acquire/renew/release go by primary key.
