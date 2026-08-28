---
type: process
universe: live
status: verified
verified: 2026-08-28
commit: 0a40fa2
consumes: []
produces: [PipelineRun, PipelineRunItem, PipelineRunItemEvent, Video, Transcription, Speaker, VideoFrame, OcrResult, MultimodalSegment, KnowledgeUnit, ContextChunk]
---

# ingest-run

URLs in, a run and everything derived from each video out. The only movement that creates a
`Video` row.

## Input → Movement → Output

A `POST /api/v1/pipelines` carrying URLs and an optional `skipPhases` set. One `PipelineRun` row and
one `PipelineRunItem` per accepted URL are created, then each item is submitted to a virtual-thread
executor and walked through the ordered phases. Output is a `Video` row per item plus whatever the
enabled phases produced.

## Why this shape

The submit is asynchronous but ownership is **not** claimed asynchronously. Ownership is taken
*before* the executor submit and the lease *after* the concurrency gate, so an item waiting behind
the gate is `PENDING` with no lease — and the in-memory ownership set is the only thing that keeps
it from being reaped. Reversing that order is what once made a crashed process leave items
unreachable forever.

## Steps

1. `PipelineController.create` accepts the body — `PipelineController.java:52-54`. If **every** URL
   is rejected it answers **400 with a `CreatePipelineRunResponse` body**, not a ProblemDetail.
2. `PipelineService.enqueuePipelineRunBatch(urls, skipPhases)` — `PipelineService.java:107`.
   `RunLifecycleService.createPipelineRun` writes the run with `phase = CREATED` and the persisted
   `skipPhases` — `RunLifecycleService.java:23`.
3. `enqueueItem` claims ownership, then submits — `PipelineService.java:284`.
4. `runPipelineRunItem` passes a `Semaphore` (`vidingest.ingestion.concurrency`, default 4) — the
   executor itself is unbounded so shutdown waits only for in-flight work — `PipelineService.java:55`, `:311`.
5. `executePhases` walks `PipelinePhaseRegistry.phases()` in order; each phase gates itself via
   `applies(ctx)` — `PipelineService.java:425`.
6. Every transition calls `RunAggregationService.updateRunPhase` and writes a
   `PipelineRunItemEvent` through `PipelineAuditService`.
7. `finalizeSuccess` then `refreshRunState` — which takes `SELECT … FOR UPDATE` on the run
   *before* reading items — `PipelineService.java:498`, `RunAggregationService.java:72`.

## If you change this

- **Hits:** the semaphore sizing, lease acquisition order, `RunAggregationService` (the only writer
  of run status), the audit feed the console reads.
- **Does not hit:** per-phase rerun. That path never creates a run row — see [re-execute](re-execute.md).

## Surfaces

| Surface | Role |
|---|---|
| `PipelineController` | entry |
| console ingest screen, `vidingest-cli`, `vidingest-mcp` | callers |

## See

- Objects: [PipelineRun](../objects/run/pipeline-run.md), [PipelineRunItem](../objects/run/pipeline-run-item.md), [PipelineRunPhase](../objects/run/pipeline-run-phase.md), [Video](../objects/media/video.md)
- Source: `.../pipeline/service/PipelineService.java`
