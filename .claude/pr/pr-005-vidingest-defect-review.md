# PR #5 — Five defects: atomicity, silent OCR loss, lost run-status updates, connection starvation

**Merged**: 2026-08-25 · **Branch**: `fix/vidingest-defect-review` · **Merge commit**: `3325028`

## Problem

1. **`@Transactional` on `protected`, self-invoked methods is inert** — twice over:
   `AnnotationTransactionAttributeSource` is `publicMethodsOnly`, and `this.` calls bypass the
   proxy anyway. Six services carried such annotations. Putting `@Transactional` on the
   repository bulk-deletes (May 2026) made each *delete* atomic but left delete-then-insert
   split across commits. `TranscriptionService` committed `full_text=null`, then a segment
   wipe, then the new segments — a failure in the third step destroyed an hour of Whisper
   output with no recovery but re-transcribing.
2. **`OcrService` lost data silently.** A frame whose JPG was gone counted as a skip, not a
   failure, so the all-failed guard never fired. Rows are wiped before the sidecar loop, so a
   video whose frame rows outlived their files lost every OCR result and returned 200 `rows=0`.
3. **`refreshRunState` lost updates.** Read items → load run → write, with no `@Version` and no
   lock. Two items finishing at once stranded the run at `IN_PROGRESS` with all items
   `COMPLETED`, permanently; the startup reconciler then marked that successful run `FAILED`.
4. **A pooled connection was held across a blocking ollama call** in
   `ContextChunkGenerationService` — up to 32 sequential HTTP requests at 180s each, against a
   10-connection pool, amplified by an unbounded ingestion executor and a 100-URL ceiling.
5. **Diarize N+1.** Detached segments meant `saveAll` merged: a SELECT per row before the UPDATE.

## Change

Seven one-concern commits. Zero new production classes — the transaction fixes inject Spring's
`TransactionOperations` and wrap only the DB block, leaving ffmpeg, the sidecar calls and
`writeTranscriptFiles` outside. Concurrency is a `Semaphore` in `PipelineService`
(`vidingest.ingestion.concurrency`, default 4). `refreshRunState` takes the run row with
`SELECT ... FOR UPDATE` before reading items; `@DynamicUpdate` on `PipelineRun` handles the
phase-only writes.

## Decisions

- **`TransactionOperations`, not new writer beans.** Three collaborator classes were drafted and
  dropped. Declaring the field as the *interface* lets unit tests pass
  `TransactionOperations.withoutTransaction()`, so test churn was one constructor arg per file
  and every existing `InOrder` assertion survived.
- **Semaphore, not a fixed thread pool.** `ExecutorService.close()` drains the queue; a fixed
  pool would have turned SIGTERM into "wait for the entire backlog to transcode". The
  virtual-thread executor has no queue, so `close()` waits only for in-flight work.
- **The run-row lock is scoped to `refreshRunState` alone.** Every audit-event insert already
  holds `FOR KEY SHARE` on that row via its FK, which a plain `UPDATE` tolerates and
  `FOR UPDATE` does not — locking `ensureRunInProgress`/`updateRunPhase` would serialise the
  hot path against its own audit trail for no correctness gain.
- **OCR and KNOWLEDGE keep their split wipe/persist.** Both wipe before a minutes-long loop; one
  transaction across it is exactly defect 4. Their inert annotations were removed with a comment
  saying why, rather than honoured.
- **Guards were proven to have teeth**, not just added: re-adding `@Transactional` to
  `regenerateFor` was confirmed to fail the integration test, and the N+1 was *measured* —
  404 → 204 prepared statements for 200 segments via Hibernate statistics.

## Deliberately not done

- **The frame-row wipe was NOT moved next to the directory wipe.** It looks like the obvious fix
  for defect 2, and it is a data-loss regression: `vidingest_ocr_results.frame_id` is
  `ON DELETE CASCADE`, so an ffmpeg failure would destroy OCR data too. It also doesn't close
  the window — `prepareFramesDir` has already deleted the JPGs by then.
- **No `hibernate.jdbc.batch_size` / `order_inserts` / `order_updates`.** Every flush on these
  paths is one homogeneous `saveAll` dwarfed by sidecar time, and batched `vector(1536)` writes
  are unverified against the driver bug at `KnowledgeUnitRepository:81-89`. The 200 unbatched
  per-row UPDATEs measured above are that decision, visible.
- **No `@Version` + retry loop for defect 3.** A Liquibase migration plus a retry wrapper to
  protect a row written a few times a second; the pessimistic lock is three lines.
- **`DiarizationService`'s dead `cleared` branch was kept.** Unreachable at runtime (the
  `ON DELETE SET NULL` FK already nulled `speaker_id`), but `DiarizationServiceTest` exercises
  it directly and it is invisible-from-Java defensiveness. The false javadoc was fixed instead.

## Follow-ups

- **ffmpeg temp-dir swap in `FrameSamplingService`** — write frames to scratch and swap on
  success, removing the root cause behind defect 2 instead of only making it loud.
- **`YoutubeSyncExecutorConfig` is still unbounded** — same shape as the ingestion executor,
  smaller blast radius (scheduler-driven, bounded by channel count, collects its futures).
- **No `ponytail:` marker on the concurrency gate**, so `/ponytail-debt` won't surface it. The
  ceiling: one global permit count treats a DOWNLOAD and a CONTEXT phase as equal cost; the
  upgrade path is weighted or per-phase permits.
