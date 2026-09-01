---
type: object
cluster: run
universe: live
status: verified
verified: 2026-08-28
commit: 0a40fa2
entity: applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/pipeline/domain/PipelineRunItemEvent.java
---

# PipelineRunItemEvent

Append-only audit row, one per thing that happened to an item. It is the **only** record of which
phases an item actually entered — the item row keeps current state, not history.

## Why this shape

- **It carries `pipeline_run_id` as well as `run_item_id`**, both `CASCADE`, so the run-wide audit
  feed is one index scan instead of a join through items.
- **Every insert holds `FOR KEY SHARE` on the run row** through that FK. That is why
  `ensureRunInProgress` / `updateRunPhase` must not take `FOR UPDATE` — it would serialise the hot
  path against its own audit trail.
- **`ITEM_PHASE_COMPLETED` exists so a phase can render as "Completed"** rather than stuck at
  "In progress" — it pairs with the preceding `ITEM_PHASE_ENTERED` (`PipelineRunItemEventType.java:5-11`).
- **`PipelineAuditService` is the only writer, and the per-phase rerun is not one of its callers.**
  `VideoPhaseRunnerService` runs a phase against the *video* row and never touches a run item, so
  it writes no event at all. Anything built from this table therefore cannot see a rerun: the
  console's lane and phase trail reload byte-identical after one, which is why `run-detail.ts`
  paints re-run timing over them client-side (`core/lane.ts` `paintRerun`).

## Shape

- `vidingest_pipeline_run_item_events` — `:23`
- `eventType` `PipelineRunItemEventType` (8 constants) — `:43`
- `phase` + `previousPhase`, `status`, `errorCode`, `errorMessage`, `videoId` — `:50`, `:54`, `:58`, `:62`, `:65`, `:68`
- `occurredAt` not updatable — `:71`
- Indexes: `(run_item_id, occurred_at)`, `(pipeline_run_id, occurred_at)`, `(event_type)` — `001-pipeline.sql:134-138`

## Connected to

- **owned-by:** [PipelineRunItem](pipeline-run-item.md) and [PipelineRun](pipeline-run.md), both `CASCADE`
- **joins:** [Video](../media/video.md) by `video_id` `ON DELETE SET NULL`
- **looks-like-but-is-not:** not a log line. Deleting a run deletes its audit trail with it.

## If you change this

- **Hits:** `PipelineAuditService` (sole writer), `PipelineAuditQueryService`
  (`MAX_PAGE_SIZE = 500`, clamped server-side, **ascending**), `PipelineAuditMapper`,
  `core/audit.ts` (`AUDIT_PAGE = 500`, `AUDIT_MAX_PAGES = 4` — mirrors the clamp by hand),
  `core/lane.ts` (an item with no `ITEM_PHASE_ENTERED` draws as ten hatched "skipped" boxes),
  and `PipelineRunItemEventType` in `core/domain.ts`.
- **Does not hit:** item or run state. Events are written *after* the transition, never read back
  to derive status.

## Surfaces

| Surface | Role |
|---|---|
| `PipelineAuditController` | reads (`/pipelines/{runId}/audit`, `/items/{itemId}/audit`) |
| `PipelineAuditQueryService` | reads, clamps page size |
| console `features/audit`, run detail, ingest | read the **tail**, not page 0 |

## See

- Source: `.../pipeline/domain/PipelineRunItemEvent.java`
- Paging trap in full: [root CLAUDE.md](../../../../CLAUDE.md) — the last *page* is not the last *window*
