---
type: object
cluster: console
universe: live
status: verified
verified: 2026-09-03
commit: 0a40fa2
entity: applications/webapp/src/app/core/watch-run.ts
---

# watchRun — the run, its audit tail, and the lanes

One function returning the four moving parts every lane-drawing screen needs, and — for the two
that start a run — the query param and the panel that go with it. Extracted because declared
inline it cost the same correction twice.

## Why this shape

- **Three screens draw lanes** — run detail, ingest and channel detail — and each had the run, the
  audit tail, the clock and the lane build declared separately. Two fixes had to land twice: the tail had to stop
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
- **The `?run=` param and the panel travel with the resources.** Ingest and channel detail both
  start a run and then watch it, and the id is the one piece of that worth a link. The channel
  screen copied ingest's panel *without* the param, so a refresh dropped the run it had just
  started; the markup was a second copy of the same 45 lines. `watchRunFromUrl` owns the param
  (`watch-run.ts:86`) and `ui/run-watch.ts` owns the panel, so neither can be half-copied again.

- **A panel with no run id still has something to say.** An ingest the server refuses outright
  creates no run, so the id is empty — the panel renders anyway, carrying the per-video reasons,
  and drops only its `full run →` link. Keying the panel on the id hid the refusals entirely on the
  channel screen (fixed Sep 2026, `channel-detail.spec.ts`).

## Shape

- `watchRun(runId)` — `watch-run.ts:27`; called from an injection context, resources die with the component
- `runId()` returning `undefined` leaves both resources idle — the ingest screen has no run until a submit answers
- `watchRunFromUrl()` — `watch-run.ts:86`; the same handle plus a `runId` signal synced to `?run=`, guarded by `isUuid`
- `WatchedRun` — `watch-run.ts:93`; what `vk-run-watch` takes as one input rather than three
- Depends on `auditTail` (`core/audit.ts`), `buildLanes` (`core/lane.ts`), `Poller` (`core/poller.ts`), `isLive`/`isUuid` (`core/domain.ts`), `syncQueryParams` (`core/url-state.ts`)

## Connected to

- **joins:** [PipelineRun](../run/pipeline-run.md), [PipelineRunItem](../run/pipeline-run-item.md), [PipelineRunItemEvent](../run/pipeline-run-item-event.md)
- **looks-like-but-is-not:** a generic poller. `Poller` is the generic one; this is run-shaped

## If you change this

- **Hits:** run detail, ingest and channel detail — that is the point of the file. `watchRunFromUrl`
  and `ui/run-watch.ts` hit only the latter two; run detail takes its id from the route.
- **Does not hit:** the runs *board*. It polls a list by status and never builds lanes.

## Surfaces

| Surface | Role |
|---|---|
| `features/runs/run-detail` | reads `watchRun`, draws its own trail |
| `features/ingest`, `features/channels/channel-detail` | read `watchRunFromUrl`, draw `vk-run-watch` |
| `ui/run-watch` | the panel; takes the handle, owns the item rows and the lane wiring |

## See

- Source: `applications/webapp/src/app/core/watch-run.ts`, `ui/run-watch.ts`, `core/audit.ts`, `core/lane.ts`
