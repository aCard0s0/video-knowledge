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

-- Supports the reconciler sweep: WHERE status = 'IN_PROGRESS' AND phase_updated_at < ?
-- then filtering on lease_expires_at, and the heartbeat's renew-by-id-and-owner update.
CREATE INDEX idx_vidingest_pipeline_run_items_lease_expires_at
    ON vidingest_pipeline_run_items (lease_expires_at);
