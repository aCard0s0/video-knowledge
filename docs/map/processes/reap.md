---
type: process
universe: live
status: verified
verified: 2026-08-28
commit: 0a40fa2
consumes: [PipelineRunItem, PipelineRun]
produces: [PipelineRunItemEvent]
---

# reap

Deciding that work is dead. The most dangerous movement in the repo — a wrong answer invites an
operator retry that runs a second worker over the same video.

## Input → Movement → Output

Items whose `phase_updated_at` is older than `vidingest.reconciler.itemStaleAfter` (default `PT1H`),
in a sweepable status. Each is tested against **two independent claims** of liveness. Only an item
neither claim covers is failed.

## Why this shape

`phase_updated_at` moves only on a phase *transition*, so a phase legitimately running for hours —
KNOWLEDGE and DIARIZE both do on a long video — looks identical to abandoned work. One answer is
not enough, and the two available answers fail in **opposite** directions: `isOwnedHere` cannot see
other instances but is never wrong about this one; the lease sees every instance but goes stale if
this process stops heartbeating. Requiring both to say "not mine" is the whole design.

The sweep covers `PENDING` too, because an item queued behind the concurrency gate has neither
started nor taken a lease — ownership is the only thing standing between it and a false reap.

## Steps

1. `StuckItemReconciler.reconcileStuckItems` runs on `vidingest.reconciler.intervalMs` (default
   300 000, initial delay 60 000) — `StuckItemReconciler.java:54-56`.
2. `findByStatusInAndPhaseUpdatedAtBefore(SWEEPABLE, threshold)` — `:57-58`.
3. Per item: skip if `RunItemLeaseService.isOwnedHere` (`RunItemLeaseService.java:108`); skip if
   the lease is live (`RunItemLeaseService.isLive`); otherwise fail it. Both answers come from the
   same service on purpose — the claim used to be a private field on `PipelineService`.
4. Leases are heartbeated by `RunItemLeaseService.renewLeases` on
   `vidingest.lease.heartbeatMs` (default 120 000) — **must stay well under `vidingest.lease.ttl`**
   — `RunItemLeaseService.java:163-165`. Renewal is scoped by owner, so a heartbeat can never extend a
   lease another instance took over.
5. Separately, at startup only: `ProgressPipelineRunReconciler.reconcileInProgressPipelineRuns` fires
   on `ApplicationReadyEvent` — **not on a schedule** — re-derives each `IN_PROGRESS` run from its
   items first, and leaves the run entirely alone if any item holds a live lease
   (`ProgressPipelineRunReconciler.java:20-21`, `:50`).

## If you change this

- **Hits:** `vidingest.lease.ttl` vs `heartbeatMs` (the invariant that keeps live work alive),
  `RunItemLeaseService` (sole writer of the lease columns), the `(status, phase_updated_at)` index
  the sweep scans, `PipelineMetrics`.
- **Does not hit:** run status directly. A reaped item goes through `RunAggregationService` like any
  other failure — the reconciler never writes a run status itself except `markFailed` at startup.

## Surfaces

| Surface | Role |
|---|---|
| `StuckItemReconciler` | scheduled sweep |
| `ProgressPipelineRunReconciler` | startup only |
| operator | sees the result as `FAILED` and retries |

## See

- Objects: [PipelineRunItem](../objects/run/pipeline-run-item.md), [PipelineRun](../objects/run/pipeline-run.md)
- Source: `.../pipeline/service/StuckItemReconciler.java`, `.../ProgressPipelineRunReconciler.java`, `.../RunItemLeaseService.java`
