# PR #9 — Process liveness, orphaned run items, torn writes

**Merged**: 2026-08-26 · **Branch**: `fix/vidingest-process-liveness-and-atomicity`

A correctness-only review of `applications/vidingest` — bugs, not design. Five defects, ranked by
blast radius. Two of them were made worse by the lease landing in
[#8](pr-008-phase-toggle-and-read-model.md).

## Problem

1. **A hung ffmpeg wedged the whole pipeline, silently.** All three call sites drained the process
   pipe with `readAllBytes()` *before* `waitFor`. The stream reaches EOF only when the process
   exits, so the drain — not the timeout — bounded the call. FRAME_SAMPLE's configured `PT20M`
   could never fire; TRANSCRIBE and DIARIZE passed no timeout at all. The thread never reached its
   `finally`, so the concurrency permit never came back and the item stayed in `inFlightItemIds` —
   where #8's heartbeat renewed its lease forever, leaving `StuckItemReconciler` with *both*
   guards saying "live". Four of those at the default concurrency of 4 and ingestion stops, with
   nothing failed and nothing logged. ffmpeg also inherited an open stdin pipe, which reaches the
   same state with no I/O stall at all.
2. **Items abandoned as PENDING were unreachable.** `StuckItemReconciler` swept `IN_PROGRESS`
   only; `refreshRunState` keeps a run `IN_PROGRESS` while any item is `PENDING`; and
   `ProgressPipelineRunReconciler` re-derives only at startup, so the run never reached a terminal
   status either. `prepareRetry` then refused it for not being FAILED. On a run that *was* FAILED,
   retry accepted only FAILED items — and called `prepareRetry` before deciding that, so a retry
   accepting nothing still moved the run out of FAILED and the next attempt was refused
   permanently. A restart mid-batch could strand URLs with no route back to them.
3. **KNOWLEDGE and OCR destroyed rows and reported success.** Both committed the wipe before a
   minutes-long loop. KNOWLEDGE's guard only fired when *every* batch failed, so nine of ten
   failing replaced a video's 300 units with the dozen the survivor produced and returned
   `rows=12` as success. Filtering everything out hit the same path.
4. **A pooled connection was held across yt-dlp.** `downloadToDatabase` was `@Transactional`; its
   repo read binds a connection and the download runs for up to 1800s. Ten concurrent
   `POST /videos/download` held all ten Hikari connections and every other DB user in the JVM
   failed waiting. `syncChannel` had the same shape around a 120s playlist fetch, four-wide per
   tick — and its `catch (RuntimeException)` wrote `ERROR` + `lastError` *inside* the transaction
   it was about to roll back, so those failures were invisible on the row.
5. **`VideoDeleteService` deleted the folder inside a transaction that could roll back.** A
   recursive delete cannot be undone, so a failed row delete left a `COMPLETED` row pointing at
   nothing.

## Change

Five one-concern commits. One new production class (`FfmpegRunner`) and one new field
(`ownedItemIds`); everything else is relocation. `-nostdin` plus a closed stdin pipe plus an
off-thread drain make every ffmpeg timeout real. The reconciler sweep covers `PENDING`, gated on
ownership claimed *before* the executor submit. Retry asks "is anyone running this?" instead of
matching on FAILED. The wipe in OCR and KNOWLEDGE moves after the loop into the insert's
transaction. `@Transactional` comes off both yt-dlp paths. The row delete precedes the `rm`.

## Decisions

- **Ownership and the lease are separate sets, not one.** `inFlightItemIds` is what the heartbeat
  renews and must stay post-gate, since a queued item holds no lease. `ownedItemIds` is claimed
  pre-submit and is the only thing that can distinguish "queued behind our gate" from "abandoned"
  for a `PENDING` row. `isItemInFlight` was deleted rather than kept alongside — it answered only
  for items past the gate, which is no longer a question anyone asks.
- **The run-level retry gate is answered before the per-item partition.** Deferring `prepareRetry`
  also deferred its validation and dropped the 409 for retrying a COMPLETED run;
  `AsyncPipelinesApiIntegrationTest` caught it. `prepareRetry` validates and mutates in one call,
  so the check had to be lifted out rather than moved.
- **KNOWLEDGE fails on any failed batch; OCR still tolerates a bad frame.** A batch is ~40
  segments of coverage, a frame is a frame. #5 settled OCR's tolerance deliberately and it stands;
  KNOWLEDGE's "salvage what worked" was never a decision, just the shape of the all-failed guard.
- **`syncChannel` split with `TransactionOperations`, not `@Transactional` helpers.** The first
  draft used `protected` methods called through `this.` — inert twice over, which is precisely the
  defect #5 removed from six services.
- **Guards proven to have teeth**, per #5: `@Transactional` was re-added to `downloadToDatabase`
  and confirmed to fail `SubprocessTransactionBoundaryIntegrationTest` before being reverted.

## Deliberately not done

- **#5's wipe/persist split was not reversed.** It looks like it was. The constraint #5 actually
  set is that the sidecar/LLM loop must not hold a pooled connection, and it does not: the wipe
  moved *after* the loop, into the two-statement transaction that was already there. Committing it
  *before* the loop was never the goal, only the means.
- **No lease on queued items**, so orphan recovery still waits `itemStaleAfter` (PT1H). Leasing
  pre-gate would make recovery immediate, but a lease is a claim on work in progress and a queued
  item is not that; the honest version needs a queue depth model this PR does not want.
- **`YtDlpExecutor` not generalised into `FfmpegRunner`.** commons-exec already solves the drain
  correctly, but that class is yt-dlp-shaped down to its error strings and reworking it drags its
  callers along for no gain.
- **`VideoDeleteService` can now leak a folder** if the process dies between commit and unlink. A
  post-commit hook or a sweeper would close it; orphaned bytes are recoverable and an unplayable
  video row is not, so the trade was taken as-is.
- **`VideoPhaseRunnerService` still runs phases outside the ingestion gate.** An operator looping
  the rerun endpoint can spawn unbounded ffmpeg. Out of scope here, and it is an authenticated
  operator escape hatch by design.

## Follow-ups

- The rerun endpoint sharing the ingestion gate, if the unbounded-fanout above ever bites.
- `OllamaEmbeddingsClient` builds a fresh `RestClient` per `embed()` call, and
  `YoutubeChannelCommandService.listChannels` issues one `countByChannel_Id` per row. Both were
  found in the same review and both are too small to have earned a commit here.
