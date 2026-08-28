---
type: process
universe: live
status: verified
verified: 2026-08-28
commit: 0a40fa2
consumes: [PipelineRun, PipelineRunItem, Video]
produces: [PipelineRunItemEvent]
---

# re-execute

Three doors back into work already done: retry a run, retry one item, or re-run **one phase** on a
video. They differ in what they consume, and that difference is not cosmetic.

## Input → Movement → Output

A run id, an item id, or a video id plus a phase name. The first two reset run-item state and go
back through the executor; the third synchronously re-executes a single phase service against the
persisted `Video`, creating no run row at all.

## Why this shape

**Absent is not empty.** Both retry endpoints take `skipPhases` from the request body, and a client
sending `{"skipPhases": []}` re-enabled every enrichment phase the run had deliberately skipped —
the runs board did exactly that, and a run created without OCR came back calling paddleocr. So
`requestedSkips` returns **null** for an absent list and the service resolves null to the run's own
persisted set; an empty list stays an explicit "run everything". Reconstructing the set client-side
is impossible: a phase after the one that failed was never reached, which no lane can distinguish
from skipped.

## Steps

1. `PipelineController.requestedSkips(request)` — `null` when the list is absent — `PipelineController.java:119`.
2. Run retry: `PipelineService.enqueueRetryBatch` — `:135`. Item retry: `enqueueRetryItem` — `:241`.
3. `resolveSkips` falls back to the run's persisted set — `PipelineService.java:280`.
4. `RunLifecycleService.prepareRetry` **validates and mutates in one call**, writes the effective
   `skipPhases` back and sets `phase = CREATED` — `RunLifecycleService.java:42`. The run-level gate
   ("only a FAILED run may be retried") is answered *first* and separately; a retry that accepts
   nothing must leave the run `FAILED`.
5. Item eligibility: not `COMPLETED`, not `CANCELLED`, not claimed — the same question the sweep
   asks. Rejections come back in the body — `PipelineService.java:212`, `:229`.
6. Per-phase rerun: `POST /api/v1/videos/{videoId}/phases/{phase}/run` →
   `VideoPhaseRunnerService.runPhase` — `VideoPhaseRunnerService.java:58`,
   `VidIngestApiPaths.java:64`. Only `TRANSCRIBE`..`CONTEXT` are reachable — `isOptional()` is the gate.

## If you change this

- **Hits:** `SkipPhasesParser` (400s on mandatory or unknown), the console phase picker (it
  describes the run in front of it because `prepareRetry` writes the set back), and any client that
  sends an empty list meaning "unchanged" — it does not mean that.
- **Does not hit:** idempotency. Every phase service is wipe-then-repopulate for the video, which is
  what makes all three doors safe to open twice.

## Surfaces

| Surface | Role |
|---|---|
| `PipelineController`, `VideoPhaseController` | entry |
| console run detail (retry), video detail (per-phase rerun) | callers — **must read the 202 body**, it carries `REJECTED` items |

## See

- Objects: [PipelineRun](../objects/run/pipeline-run.md), [PipelineRunPhase](../objects/run/pipeline-run-phase.md), [Video](../objects/media/video.md)
- [docs/vidingest/VidIngest - Per-Phase Rerun.md](../../vidingest/VidIngest%20-%20Per-Phase%20Rerun.md)
