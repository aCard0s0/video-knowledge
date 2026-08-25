# PR #7 — Reconciler liveness, ffmpeg staging dir, youtube sync bound

**Merged**: 2026-08-25 · **Branch**: `fix/reconciler-liveness-and-fanout-bounds`

The three follow-ups [#5](pr-005-vidingest-defect-review.md) left open, plus one it did not
catch.

## Problem

**The reconciler failed live work.** `phase_updated_at` moves only on a phase *transition*,
never during one. The per-phase timings recorded in the rerun doc are for a ~12 minute video —
KNOWLEDGE ~800–850s, DIARIZE ~430–450s — so a 90-minute video pushes a single phase well past
the `PT1H` `itemStaleAfter` default with its timestamp frozen throughout. `StuckItemReconciler`
then marks a healthy item FAILED and nothing cancels the worker. The worker later flips it back
to COMPLETED (bogus `ITEM_FAILED` event, double-counted metric), or an operator sees FAILED and
retries — starting a **second worker wipe-and-repopulating the same video alongside the first**.
#5 made each individual replace atomic; nothing stopped two of them interleaving.

**ffmpeg destroyed the previous frames before it ran.** `prepareFramesDir` emptied the live
`frames/` dir up front, so a failure or a 20-minute timeout left `vidingest_video_frames` rows
pointing at JPGs that no longer existed. #5 made the consequence loud but left the cause.

**The youtube sync tick was unbounded.** One task per enabled channel onto an unbounded
executor, each running a yt-dlp playlist fetch — 200 channels means 200 processes at once.

## Change

- `PipelineService` records which items are executing phases in this JVM; the reconciler skips
  those. Items abandoned by a *previous* process are absent, which is exactly what it should reap.
- ffmpeg writes into `frames.staging/`, promoted into `frames/` only after a clean exit. A
  failure deletes staging and leaves the previous frames and rows untouched.
- A `Semaphore` in the sync scheduler (`vidingest.youtube.sync.concurrency`, default 4).

## Decisions

- **Liveness beats tuning.** Raising `itemStaleAfter` only moves the cliff — any fixed timeout is
  wrong for some video length. Asking whether the item is actually running is exact, so the
  `PT1H` default is left alone and no longer bounds how long a healthy phase may run.
- **The in-flight set lives on `PipelineService`**, not a new registry bean. It already owns the
  concurrency gate and there is no cycle; extracting it is worth doing only if a third consumer
  appears. It is a `ConcurrentHashMap.newKeySet()` — mutable singleton state, but correctly
  published and inherent to tracking live work.
- **Staging, not early row-wipe.** Deleting the rows up front to match the emptied directory was
  the obvious-looking fix and was rejected during #5: `vidingest_ocr_results.frame_id` is
  `ON DELETE CASCADE`, so it would destroy the video's OCR on every ffmpeg failure.
- **Semaphore again for youtube sync**, matching the ingestion gate, so the executor keeps
  shutdown semantics that wait only for in-flight work rather than draining a queue.

## Deliberately not done

- **No shared lease for multi-instance deployments.** `isItemInFlight` is per-JVM. A second
  instance would reap the first's live work — but nothing else here is multi-instance safe
  either (`ProgressPipelineRunReconciler` fails every IN_PROGRESS run at *its* startup), so a
  half-measure would be misleading. Documented as a per-JVM guarantee instead.
- **No CI**, declined for now. Nothing enforces the 335 tests but a local run.

## Follow-ups

- A multi-instance deployment needs a shared lease before either reconciler is safe.
