---
type: object
cluster: run
universe: live
status: verified
verified: 2026-08-28
commit: 0a40fa2
entity: applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/pipeline/domain/PipelineRunItem.java
---

# PipelineRunItem

**One URL** inside a run. This is where the pipeline actually runs, where failure is recorded, and
where the lease lives. If you are debugging "a run", you are almost always debugging an item.

## Why this shape

- **`failedPhase` is not always a phase.** `CREATED` for an item reaped while still queued, `DONE`
  on a clean finish. `LANE_PHASES.indexOf` answers `-1` on both — the console calls `isLanePhase()`
  first.
- **The lease pair exists because `phase_updated_at` cannot tell you anything.** It moves only on a
  phase *transition*, so a phase legitimately running for hours is indistinguishable from abandoned
  work. Two independent answers guard reaping, and they fail in opposite directions:
  `RunItemLeaseService.isOwnedHere` is blind to other instances but never wrong about this one;
  `lease_owner` / `lease_expires_at` see every instance but go stale if this process stops
  heartbeating. An item is reaped only when **neither** claims it.
- **Ownership is claimed before the executor submit, the lease after the concurrency gate.** The
  gap is the point: an item queued behind the gate is `PENDING` with **no lease**, so the sweep
  covers `PENDING` too and the local claim is the only thing keeping queued work alive. Before that,
  a process dying with items queued left them unreachable and every retry refused.

## Shape

- `vidingest_pipeline_run_items`, `UNIQUE (pipeline_run_id, url)` — `PipelineRunItem.java:16`, `001-pipeline.sql:100`
- `status`, `phase`, `failedPhase`, `errorCode`, `error` — `:37`, `:41`, `:45`, `:52`, `:55`
- `videoId` `ON DELETE SET NULL`, `attempt` — `:58`, `:61`
- `leaseOwner` (160) + `leaseExpiresAt` — `:70`, `:73`
- Sweep index `(status, phase_updated_at)` — `001-pipeline.sql:112`

## Connected to

- **owned-by:** [PipelineRun](pipeline-run.md) (`CASCADE`)
- **owns:** [PipelineRunItemEvent](pipeline-run-item-event.md) (`CASCADE`)
- **joins:** [Video](../media/video.md) by `video_id`, set at PERSIST
- **looks-like-but-is-not:** `attempt` is not a retry *count* on the run — retries reuse the same run id and item id.

## If you change this

- **Hits:** `RunItemLifecycleService`, `RunItemLeaseService` (every lease write, the in-memory
  claim, and the `renewLeases` heartbeat — which must stay well under `vidingest.lease.ttl`),
  `StuckItemReconciler`, `ProgressPipelineRunReconciler`, `RunDetailsMapper`, and the console's
  lane build (`core/lane.ts`) which reads `failedPhase`.
- **Does not hit:** run status directly. Changing an item status without calling
  `RunAggregationService.refreshRunState` leaves the run where it was.

## Surfaces

| Surface | Role |
|---|---|
| `PipelineService` | writes status/phase, owns the in-memory claim |
| `RunItemLeaseService` | sole writer of `lease_owner` / `lease_expires_at`, scoped by owner |
| console run detail + ingest | read; both draw lanes from it |

## See

- Source: `.../pipeline/domain/PipelineRunItem.java`, `.../pipeline/service/RunItemLeaseService.java`
- [processes/reap.md](../../processes/reap.md), [processes/re-execute.md](../../processes/re-execute.md)
