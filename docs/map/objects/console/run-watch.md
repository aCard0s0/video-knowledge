---
type: object
cluster: console
universe: live
status: verified
verified: 2026-08-28
commit: 0a40fa2
entity: applications/webapp/src/app/core/watch-run.ts
---

# watchRun — the run, its audit tail, and the lanes

One function returning the four moving parts both lane-drawing screens need. Extracted because
declared inline it cost the same correction twice.

## Why this shape

- **Two screens draw lanes** — run detail and ingest — and both had the run, the audit tail, the
  clock and the lane build declared separately. Two fixes had to land twice: the tail had to stop
  taking the last *page* of an ascending feed, and the lane build had to move out of the template's
  read path. A third correction would have landed in one file and not the other (`watch-run.ts:11-18`).
- **The audit tail is not page N.** `size` is clamped to 500 server-side on an **ascending** feed,
  so page 0 is the oldest window and the last page holds `total mod 500` events — one, on a
  501-event run. On a 100-URL run (~2200 events) taking page 4 alone left ninety items with no
  events, and `buildLane` draws an item with no `ITEM_PHASE_ENTERED` as ten hatched "skipped"
  boxes: phases that ran reported themselves as turned off. `auditTail` takes whole pages **from
  the end**, capped at four (`core/audit.ts:6`, `:13`, `:40`).
- **The resources are returned, not just their contents.** A template must branch on `run.error()`
  *before* it may read a value — `resource.value()` throws `ResourceValueError` in the error state.
- **`?live=true` on `/pipelines` is only honoured together with `ids`.** Alone it silently returns
  every run, so the board queries `status=IN_PROGRESS` and `status=PENDING` instead.

## Shape

- `watchRun(runId)` — `watch-run.ts:26`; called from an injection context, resources die with the component
- `runId()` returning `undefined` leaves both resources idle — the ingest screen has no run until a submit answers
- Depends on `auditTail` (`core/audit.ts`), `buildLanes` (`core/lane.ts`), `Poller` (`core/poller.ts`), `isLive` (`core/domain.ts`)

## Connected to

- **joins:** [PipelineRun](../run/pipeline-run.md), [PipelineRunItem](../run/pipeline-run-item.md), [PipelineRunItemEvent](../run/pipeline-run-item-event.md)
- **looks-like-but-is-not:** a generic poller. `Poller` is the generic one; this is run-shaped

## If you change this

- **Hits:** run detail and ingest, both — that is the point of the file.
- **Does not hit:** the runs *board*. It polls a list by status and never builds lanes.

## Surfaces

| Surface | Role |
|---|---|
| `features/runs/run-detail`, `features/ingest` | read |

## See

- Source: `applications/webapp/src/app/core/watch-run.ts`, `core/audit.ts`, `core/lane.ts`
