---
type: object
cluster: run
universe: live
status: verified
verified: 2026-08-28
commit: 0a40fa2
entity: applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/pipeline/domain/PipelineRun.java
---

# PipelineRun

One submission of **one or more URLs**. The console calls it a "run"; so does this card. It holds
no work — the work is on its items.

## Why this shape

Three fields exist because something broke without them.

- **`status` has exactly one writer.** `RunAggregationService` — `RunLifecycleService` creates and
  resets runs but never writes status. Two items finishing at once used to strand a run at
  `IN_PROGRESS` forever, so `refreshRunState` takes `SELECT … FOR UPDATE` *before* reading items.
- **The lock stops there.** `ensureRunInProgress` / `updateRunPhase` fire on every phase
  transition, and each audit insert already holds `FOR KEY SHARE` on this row through its FK.
  `@DynamicUpdate` covers their whole-row clobber instead. Do not widen the lock.
- **`skipPhases` is persisted** because nothing else records what a run was configured to do. It
  lived only in memory once, and a retry body of `{"skipPhases": []}` re-enabled every enrichment
  phase the run had deliberately skipped.

## Shape

- `vidingest_pipeline_runs` — `PipelineRun.java:19`
- `status` `RunStatus`, `phase` `PipelineRunPhase` (may be `CREATED`/`DONE`) — `:37`, `:44`
- `skipPhases` `Set<PipelineRunPhase>` via `PhaseSetConverter`, **one comma-separated TEXT column** — `:64-67`
- `error` + `errorCode` `PipelineErrorCode` — `:39`, `:51`
- `@DynamicUpdate` — `:23`
- `FOR UPDATE` read: `PipelineRunRepository.java:56-57`
- Index `(status, created_at DESC)`; `created_at` alone added later — `001-pipeline.sql:38`, `007-run-created-at-index.sql`

## Connected to

- **owns:** [PipelineRunItem](pipeline-run-item.md) (`ON DELETE CASCADE`), [PipelineRunItemEvent](pipeline-run-item-event.md) (`CASCADE`)
- **joins:** [Video](../media/video.md) via `videos.pipeline_run_id` `ON DELETE SET NULL` — the run that *last* touched it, not ownership (`001-pipeline.sql:46-49`)
- **looks-like-but-is-not:** `PipelineRunItem`. A run has no URL of its own worth trusting — `videoUrl` (`:53`) is the first URL only.

## If you change this

- **Hits:** `RunAggregationService` (every status write), `PhaseSetConverter` if `skipPhases`
  changes shape, `RunSummaryMapper` / `RunDetailsMapper`, the generated console client
  (`npm run api:gen`), and `RunStatus` in `applications/webapp/src/app/core/domain.ts`.
- **Does not hit:** item status. Item → run is a **roll-up**, never a mirror; writing run status
  does not touch items, and the reverse goes through `refreshRunState`.

## Surfaces

| Surface | Role |
|---|---|
| `PipelineController` | reads/writes (create, retry, list, get) |
| console `features/runs` | reads; polls `status=IN_PROGRESS` + `status=PENDING` |
| `vidingest-mcp`, `vidingest-cli` | read via `vidingest-client` |

## See

- Source: `applications/vidingest/vidingest-server/.../pipeline/domain/PipelineRun.java`
- [processes/ingest-run.md](../../processes/ingest-run.md)
