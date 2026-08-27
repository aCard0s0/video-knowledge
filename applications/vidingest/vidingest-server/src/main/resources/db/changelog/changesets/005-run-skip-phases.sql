-- liquibase formatted sql
-- changeset vidingest:005-run-skip-phases

-- What a run was configured *not* to do.
--
-- skipPhases lived only in the in-memory PipelinePhaseContext, so once a run was over nothing
-- could say which phases it had been set up to run. Both retry endpoints take the set from the
-- request body, so every client that omitted or emptied it re-enabled the enrichment phases the
-- run had deliberately skipped — the runs board sent `{"skipPhases": []}` and turned OCR and
-- KNOWLEDGE back on for a run created without them.
--
-- One comma-separated column, not a child table: at most seven names, always read and written
-- whole, never queried by member. NULL and '' both mean "nothing skipped", which is exactly what
-- every row written before this column existed meant.
ALTER TABLE vidingest_pipeline_runs
    ADD COLUMN skip_phases TEXT;
