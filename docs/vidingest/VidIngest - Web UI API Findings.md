---
type: findings
last_reviewed: 2026-08-27
---

# VidIngest — Web UI API findings

What the API **does**, measured against a running server rather than read off the source. Split out
of [VidIngest - Web UI](VidIngest%20-%20Web%20UI.md), which is the hub for this page.

These are the behaviours that cost the console a bug each: a 400 that carries a success body, a 503
that carries the full report, a 202 that does not mean queued. Read this before writing a call, not
after debugging one.

## API discovery findings

Measured against the running server on 2026-08-26 (18 runs, 3 videos, 303 audit events),
not inferred from source. 40 operations, 42 schemas (39 before `deleteChannel`).

### 1. Errors are RFC 9457 ProblemDetail, not `ApiErrorResponse`

`VidingestApiExceptionHandler` returns `ProblemDetail`. `common-web`'s `ApiErrorResponse` is
**not** used by this server.

```json
{"status":404,"title":"Not found","detail":"Pipeline run not found: 000…","instance":"/vidingest/api/v1/pipelines/000…"}
{"status":400,"title":"Validation failed","detail":"Request validation failed.","fields":{"urls":"must not be empty"}}
```

Titles are a closed set: `Bad request`, `Validation failed` (adds `fields`), `Not found`,
`Conflict`, `Upstream failure` (502), `Internal error`. Every response also carries an
`X-Correlation-Id` header — that is the log grep key, so the error panel shows it.

**No operation documents any 4xx/5xx** (0 of 40) and no error schema exists in the spec, so the
generated client types error bodies as `any`. The frontend declares the `ProblemDetail` interface
by hand; annotating 40 endpoints server-side is not worth it.

### 2. `errorCode` is a pipeline field, not an HTTP field

HTTP errors carry no code. `errorCode` (`PipelineErrorCode`: `DUPLICATE_VIDEO`,
`UPSTREAM_TOOL_FAILURE`, `TRANSCRIPTION_FAILURE`, `INVALID_METADATA`, `UNEXPECTED`) appears only
on runs, run items and audit events. Two error renderers, never merged into one "something went
wrong".

Which failure a screen *shows* is one rule, not seven: `firstFailure` (`core/problem.ts`) takes the
screen's resources in precedence order, and the action the operator just took goes in front of it as
`actionFailure() ?? firstFailure(…)` — already an `ApiFailure`, so it needs no translation. Run detail is the exception on purpose — it gives the retry its own adjacent
panel, and video detail keeps a second panel for the artifact panes.

### 3. A FAILED run reports `phase: "DONE"`

Observed on a real failed run: `status FAILED`, `phase DONE`, `items[0].failedPhase OCR`. The
run-level `phase` must **never** be rendered as "where it died" — the phase rail is driven by
`item.failedPhase` plus the audit trail.

**And `failedPhase` is not always a phase.** An item the reconciler sweeps while it is still
queued blames `CREATED` (`"reconciler: stuck PENDING in phase CREATED since …"`), and a clean
finish reports `DONE`. Both are run markers, so `LANE_PHASES.indexOf` answers `-1` for them —
which is how three FAILED items once rendered as ten blank boxes announcing "complete". Anything
that treats `failedPhase` as a position asks `isLanePhase()` first; the console renders that case
as "never started", not as a place.

**`CREATED` is the same trap one field over.** A run is created with `phase = CREATED` and
`RunLifecycleService.prepareRetry` writes it back, so it is what a run reports for the seconds
between the operator pressing Retry and METADATA starting — exactly the window the runs board's
live panel exists to show. The board renders that as `queued`, which is where the run actually is
(behind the ingestion semaphore), and asks `isLanePhase()` rather than printing the marker. The
board's history table shows `—` instead: a terminal run's phase is never a place, and the reason it
stopped is rendered under it by `vk-fault`, not squeezed into the phase column.

### 4. The audit trail is the only source of per-phase durations

`ITEM_PHASE_ENTERED` / `ITEM_PHASE_COMPLETED` pairs (ascending, paged) reconstruct how long each
phase took. `previousPhase` is populated on `ENTERED` only. This is what separates "hung" from
"slow" — computed client-side, no backend change.

`PipelineRunItemEventType`: `ITEM_CREATED`, `ITEM_PHASE_ENTERED`, `ITEM_PHASE_COMPLETED`,
`ITEM_VIDEO_ATTACHED`, `ITEM_COMPLETED`, `ITEM_FAILED`, `ITEM_CANCELLED`, `ITEM_RETRY_REQUESTED`.

### 5. Timestamps carry an offset (fixed 2026-08-27 — was a measured 1h skew)

Fields serialize as `"2026-08-26T15:49:24.522757Z"`. `new Date` is enough, and `parseServerTime`
in `core/time.ts` is now just that.

It used to be `"2026-08-26T15:49:24.522757"` with no offset, because the entities were
`LocalDateTime`. The container runs **UTC** and the host browser was **WEST (UTC+1)**, so a browser
reading the naive string as local time made every age **one hour too old** — exactly the signal the
runs board exists to provide. The client's answer was to append `Z`, which was only right while the
server itself ran UTC.

The server side is fixed: entities are `OffsetDateTime`, every `now()` is
`OffsetDateTime.now(ZoneOffset.UTC)`, and `MetadataExtractor` reads yt-dlp's epoch through
`ZoneOffset.UTC` instead of `ZoneId.systemDefault()`. Asserted from a JVM pinned to
`America/Los_Angeles`, and verified end to end by running the jar in that zone: wire and stored
instant both came back UTC.

**The appended `Z` was deliberately not kept as a fallback.** It would silently re-assume UTC for
any field that lost its offset, which is how the skew hid in the first place. Fixture literals in
specs must carry the offset too — `lane.spec.ts` compared a zoneless literal against a `Z` one and
failed by exactly 3600000 ms.

**The same rule binds the *write* path, and the audit feed shipped breaking it.** `fromDate` and
`toDate` on `GET /audit/events` are `OffsetDateTime` parameters, so a zoneless value is a 400, not a
misread hour: `Failed to convert value of type 'java.lang.String' to required type
'java.time.OffsetDateTime'`. The screen sent `toISOString().slice(0, 19)` — the right instant with
the `Z` cut off — which was correct only while the server compared naive `LocalDateTime`, and
inverted the day that migration landed. So **every date filter on the audit screen 400ed**, replacing
the table with the panel reporting it and re-firing on the 15s poll. `toISOString()` alone is both
halves of the conversion the input needs: local wall clock → the UTC instant it names, carrying the
offset that says so.

### 6. Absent values are `""`, not `null`

`errorCode`, `error`, `videoId`, `channelName`, `videoTitle`, `previousPhase` all come back as
empty strings. One `blank()` guard keeps empty badges from rendering.

`VideoSummary.pipelineId` is the same: `""` once the run row is gone, since
`vidingest_videos.pipeline_run_id` is `ON DELETE SET NULL`. The videos list guards it with `blank()`
and renders `—` rather than a link to `/runs/`, which is what an unguarded `routerLink` would build.

`videoCount` is the numeric equivalent: it counts the **video rows attached to the run**
(`RunSummaryPageService` groups `findRunVideoPreviews`), not the URLs submitted, so a run that died
before PERSIST and a COMPLETED run whose video was later deleted both report `0`. The board renders
that as `—`.

### 7. No status/phase enums in the spec

Only `KnowledgeUnitType` and `ItemResult.status` are typed as enums; run status, item status,
phase, event type and video status are plain `string`. The frontend mirrors seven server enums as
TS unions and drifts if the server adds a constant:

- `RunStatus`: PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED
- `PipelineRunPhase`: CREATED, METADATA, DOWNLOAD, PERSIST, TRANSCRIBE, DIARIZE, FRAME_SAMPLE, OCR, FUSE, KNOWLEDGE, CONTEXT, DONE — **CREATED and DONE are run markers, never rendered as steps**
- `VideoStatus`: PENDING, DOWNLOADING, DOWNLOADED, EXTRACTING, TRANSCRIBING, PROCESSING, COMPLETED, FAILED
- `YoutubeChannelStatus`: NEW, SYNCING, READY, ERROR
- `TranscriptionStatus`: TRANSCRIBING, COMPLETED, FAILED
- `PipelineErrorCode`, `PipelineRunItemEventType`: see above

`PipelineErrorCode` was mirrored late (`ERROR_CODES`): the codes were only ever *rendered*, by a
`vk-fault` that takes whatever the server sent, so nothing needed the list until the audit feed
offered them as a filter.

Skippable and rerunnable phases are the same seven (`PipelineRunPhase.isOptional()`), confirmed
live: `Unsupported phase: METADATA. Allowed: TRANSCRIBE, DIARIZE, FRAME_SAMPLE, OCR, FUSE,
KNOWLEDGE, CONTEXT`.

### 8. Colliding operationIds — fixed

springdoc derived operationIds from method names, so `list`/`get`/`create`/`search`/`listForVideo`
collided across controllers and the generated client got `list1()`, `get2()`, `search1()`,
`_delete()`. Every REST method now carries an explicit
`@Operation(operationId = …)` (`listRuns`, `getRun`, `retryRun`, `retryRunItem`, `listVideos`,
`getVideo`, `deleteVideo`, `listChannels`, `syncChannel`, `createRunsFromChannel`,
`searchKnowledge`, `runVideoPhase`, …). The spec is better for the MCP and CLI consumers too.

### 9. Video streaming and Range are verified

`GET /videos/{videoId}/file` returns `Accept-Ranges: bytes` and answers `Range: bytes=100-200`
with `206` + `Content-Range: bytes 100-200/21089829`. Player seeking works.

`Content-Disposition: attachment` is set on `/file`, `/transcription/whisper.*` and
`/frames/{frameId}/image`. Media elements ignore it in practice; if a browser refuses,
switching those three to `inline` is a one-line server fix.

### 10. Paging is inconsistent, and one endpoint is unbounded

`/ocr/frames` defaults `size=25`, `/multimodal-timeline/page` defaults `size=50`, the rest
document no default and fall back to server-side defaults. The non-paged `GET /videos/{videoId}/ocr`
can return up to `vidingest.ocr.max-results-per-video` (10 000) rows — the UI always uses
`/ocr/frames`, and always sends explicit `page` and `size`.

**`size` is silently clamped.** `PipelineAuditQueryService` caps every audit page at
`MAX_PAGE_SIZE = 500` and defaults to 100, so the run screen's old `size=1000` was never
honoured and its "showing 500 of N" banner blamed a limit the client did not set. The trail is
also **ascending**, so page 0 is the *oldest* window and the tail is what the screen needs — losing
the early phases of a long run costs a duration, losing the tail costs the `ITEM_FAILED` the screen
exists to show.

  The last *page* is not the last *window*, though: it holds `total mod 500` events, so a 501-event
  run returned **one**, and a 100-URL run (~2200 events) returned the 200 on page 4. Items with no
  events in hand are not drawn as unknown — `buildLane` sees no `ITEM_PHASE_ENTERED` and renders ten
  hatched "skipped" boxes, so ninety items that had run every phase reported every phase as turned
  off. `core/audit.ts` (`auditTail`) now takes whole pages from the end, capped at four (2000
  events), and the run screen's banner reports the shortfall past that.

### 11. Three more spec defects — two fixed

- **`tags.video-artifacts` was declared twice** (download artifacts and multimodal artifacts), so
  openapi-generator refused the spec. `VideoMultimodalArtifactsController` now uses
  `video-multimodal`, and codegen no longer needs `--skip-validate-spec`.
- **`ReadinessResult` had no schema** because `HealthController.readiness()` returned
  `ResponseEntity<?>`; the client saw a bare `object`. Now `ResponseEntity<ReadinessResult>`, and
  the generated client is typed. The endpoint also answers **503 when any check fails**, and the
  `ReadinessResult` rides that response — so the rail foot reads the checks out of
  `HttpErrorResponse.error` and reserves "server unreachable" for status 0. Reporting a 503 as
  unreachable pointed the operator at a server that was up and had already named the fault
  (`videoPath: not-writable: /data/videos`).
- **`live=true` is only honoured together with `ids`.** `PipelineController.list` switches to
  `runLiveSummaryService.listLiveSummariesInOrder(ids)` only when both are present, and otherwise
  falls through to the plain paged list — so `?live=true` alone returns everything, COMPLETED runs
  included. The runs board asks for `status=IN_PROGRESS` and `status=PENDING` instead.
- **`createdAfter` bounds the listing server-side, and takes an instant rather than a named range**
  (added 2026-08-28). The ingest panel's today/week/all chips send local midnight with its offset:
  the server cannot know which midnight the caller means, and the caller already does. It replaced
  a client-side cut of one 200-row page, which is a *range* only while every run fits in that page
  and silently a *window* past it — `total` now counts the range, so the panel's "showing the
  newest N" line is one comparison rather than an inference from the oldest row in hand. The other
  list endpoints (`/videos`, `/youtube/channels`, `/audit`) still have no date bound.

### 12. `uniqueItems` generates `Set<string>`, which cannot be serialized

`skipPhases` is a Java `Set`, so the spec marks it `uniqueItems: true` and typescript-angular
generates `Set<string>`. `JSON.stringify(new Set(['OCR']))` is `"{}"`, so the field silently
arrived as an object and the server answered:

```
400 Bad request — Request body could not be read: Cannot deserialize value of type
`java.util.HashSet<java.lang.String>` from Object value (token `JsonToken.START_OBJECT`)
```

Codegen runs with `--type-mappings=set=Array` so these fields are plain arrays. Anything that
adds a new `Set`-typed request field inherits the fix automatically.

### 13. `POST /pipelines` answers 202, or 400 when nothing was accepted

`PipelineController.create` returns `BAD_REQUEST` with a `CreatePipelineRunResponse` body (not a
ProblemDetail) when `runId` is null — i.e. when every URL was rejected. The console reads the
rejects out of either shape.

### 14. Misc

- `?status` on `GET /pipelines` accepts `RunStatus` ∪ `ALL`; an unknown value 400s with the raw
  Java enum message, so the UI offers a fixed set of chips.
- `?sortBy` accepts `createdAt` (default) or `updatedAt`, descending, and **silently falls back**
  rather than 400ing: the value reaches `Sort.by` as a JPA property name, so an unknown one would be
  a 500 from inside the persistence layer and an attacker-chosen one a way to order by — and so
  probe — any field on the entity. A view preference is not part of what was asked for, so a bad
  one is ignored rather than failing the request.
- `202` on exactly three operations: run retry, item retry, channel→pipelines. `POST /pipelines`
  returns `200`. All are treated as fire-then-poll regardless — but **a 202 is not a queue
  receipt**. `PipelineService.enqueueRetryBatch` answers with a per-item verdict, and every item
  can come back `REJECTED` with a reason ("item is already running", "was cancelled"), in which
  case nothing was queued at all. Both screens that fire a retry render those through `vk-rejects`,
  in warn rather than the failure ramp: nothing broke, the server declined.
- `GET /pipelines?live=true` is the cheap poll for the live zone of the runs board.

### 15. A channel catalog has no upload dates, so its order rests on two fallbacks

`YoutubeChannelVideoSummary.publishedAt` is typed `date-time` and is **null on every row** —
yt-dlp's `--flat-playlist` emits no upload date for a channel tab, measured 0 of 200 populated on
a real sync. So the `Published` column is empty for a whole catalog, and the sort falls through to
its fallbacks every time.

Which is what made the screen open on the wrong end. `upsertVideos` keeps existing rows and
inserts new ones, stamping `createdAt` per row in `@PrePersist` while it saves the discovered list
in one pass — and yt-dlp lists a channel newest first. So `firstSeenAt` (one timestamp per sync
batch) orders the batches and `createdAt` **ascending** preserves discovery order inside one.
Sorting `createdAt` descending, as the original did, reversed each batch: the first sync puts the
whole catalog in one batch, so page 0 held the fifty *oldest* uploads and the newest was on the
last page — on the screen whose reason to exist is catching new ones.

The catalog is also capped: `vidingest.youtube.sync.playlistLimit=200` becomes `--playlist-end`,
so "200 in catalog" on a 700-upload channel is a window, not a count.

### 16. There was no way to stop tracking a channel

Measured when a fifth status, `DISABLED`, was still declared: `YoutubeChannelSyncScheduler` swept
`findAllByStatusNot(DISABLED)` every half hour and no endpoint ever set it, so a mistyped URL sat
`ERROR` forever with yt-dlp re-run against it on a cron. **That constant has since been deleted**
along with the two guards that branched on it; the sweep is now a plain `findAll()`. What the
finding produced is the endpoint: `DELETE /youtube/channels/{channelId}` answers 204, or 404 for an id that is
already gone; the discovered catalog follows through the `ON DELETE CASCADE` on
`vidingest_youtube_channel_videos.channel_id`, and videos already ingested from the channel are
untouched — they are `vidingest_videos` rows with no FK back.

### 17. `skipPhases` is only half of what a run will do

Every optional phase gates on a `vidingest.<phase>.enabled` property as well as on the request's
opt-out, and four of them default to `false`. Nothing exposed that, so the phase picker showed all
seven ticked over "all optional phases enabled" while the server was configured to skip most of
them — a batch submitted for OCR and knowledge extraction came back with neither and no screen had
said so.

`GET /pipelines/capabilities` answers with `enabledPhases` and `channelSyncLimit`. It asks the
phases themselves — `applies(ctx)` against a context that skips nothing isolates the master switch
— rather than re-reading five config beans, which would drift the first time a phase gained a
dependency. `core/capabilities.ts` is a root singleton so the screens share one request; it treats
*unknown* as enabled, because marking a phase unavailable on a failed fetch is a worse lie than the
one it fixes.

The **dependent**-phase gates are mirrored client-side in `core/domain.ts` as `PHASE_REQUIRES` (OCR
needs FRAME_SAMPLE, DIARIZE needs TRANSCRIBE) — the server skips those silently too, so unticking
FRAME_SAMPLE while OCR stayed ticked was the same lie one gate over. The picker separates the two
causes because the operator can act on only one of them, and spells each out in text rather than in
a `title` attribute, which is unreachable by touch and by keyboard.

**A phase the server has off is never added to `skipPhases`**: that set records what the *operator*
chose, and a retry inherits it as a deliberate opt-out.

### 18. A client-side filter over a server page makes the pager lie

`GET /youtube/channels/{id}/videos` takes `notIngestedOnly`. The console filtered its own page
instead, so the total the pager rendered described a different set from the rows: ingest thirty and
page 0 showed twenty rows under "1–50 of 200", and a mostly-ingested catalog became four pages of
near-empty tables. The rows have to be cut before they are counted, which only the query can do —
`not exists` against `vidingest_videos` on the same `(source, sourceVideoId)` identity that marks
the rows it leaves out.

### 19. The same screen shape, drifting in four places

Merging the ingest, channels, runs-board and empty-state branches put four screens built from the
same helpers side by side for the first time, and three corrections turned out to have landed on
some of them and not the rest. None of these is a new idea — each is an existing fix that stopped
one screen short.

**The empty state over a failed load** (finding 14) was applied to videos, audit and channels, with
a spec asserting it across all three "because one screen passing says nothing about the other two".
The runs board and the channel catalog have the identical shape and were missed: a dead
`/pipelines` rendered "No runs match this filter." over a **Start an ingest →** link, and a dead
videos query rendered "Every upload in this catalog is already ingested". Both now check
`hasValue()` first, and `empty-state.spec.ts` covers the board.

**`vk-lane` takes a `status`** for the one case no segment can express: the item is over and
nothing on the track ran, so `failedPhase` is the `CREATED` marker and there is no segment to cap.
Ingest and run detail passed it; the channel catalog did not, so a batch item reaped while still
queued announced itself **complete** there and failed on the other two. Its lane was also unwired —
ten focusable buttons per item that did nothing, the same defect ingest had already fixed. Both
fixed, and `ui/lane.spec.ts` now pins the summary text with and without the input, since the
component is where all three screens meet.

**One picker, one answer.** Which phases a deployment runs reached the picker two ways at once — an
input threaded in by ingest, and the `Capabilities` singleton read by channel detail — so run
detail, which passed neither, silently offered all seven. The input is gone; the picker injects the
singleton, which is also why the second endpoint answering this question (`GET /health/phases`)
was retired in favour of `GET /pipelines/capabilities` (finding 17).

Smaller: the watch panel's age now carries its absolute time in a `title`, as every other age on
every screen already did, and the catalog's item label goes through `shortUrl` rather than
`.truncate`, for the reason `core/url.ts` gives.

### 20. A standards pass over all eight screens

Reading the eight screens against each other — the same exercise finding 19 ran over four — turned
up nine more corrections that had stopped one or two screens short. As before, none is a new idea.

**The pinned actions column existed twice and was missing once.** `videos.scss` and `runs.scss` each
carried their own `.stick`, `tbody tr { background }` and focus-ring lift: the same four rules under
two roofs, which is exactly how `.chips` drifted before it was moved. They are now `.stick` and
`.grid-pinned` in `styles.scss`. The channels list, whose action cell is the *widest* of the three —
Sync now **and** Remove — had no pin at all, so on a phone both buttons sat off the right edge. It
takes the shared pair. What stays in `runs.scss` is the one rule only that screen needs: a run and
its fault row are one record, so hovering either half lights both.

**The video screen never refreshed.** The videos list was corrected for exactly this — "a row left
TRANSCRIBING stayed TRANSCRIBING for as long as the screen was open" — and opening that row made it
worse, not better: status, artifact counts and the whole dossier come from `/detail`, and every one
of them sat frozen while the rail underneath ticked "updated 2.0s ago". `Poller` was injected there
only to move the ages. It now polls on the same `moving() ? POLL_LIVE : POLL_IDLE` cadence the two
list screens use, reloading `/detail` and the visible pane, and skips a tick while a per-phase rerun
is in flight — that request answers with the counts itself, and a poll landing mid-wipe would show
the artifacts half-deleted.

**Two more query params reached a parse unguarded.** `?pane=BOGUS` on `/videos/{id}` matched no
`@switch` case and rendered an artifact column that was simply blank, no tab lit and nothing saying
why; `?type=BOGUS` goes into `listVideoKnowledge` and comes back a 400 carrying a raw Java enum
name. Both are allow-listed now, the rule `/audit`'s four selects already followed.

**`?run=` is a uuid, and neither screen said so.** Ingest and channel detail both carry the watched
run in the URL, and both fed it straight to `GET /pipelines/{id}` — which takes a whole uuid and
answers a raw conversion error for anything else. The id those screens *print* is `id.slice(0, 8)`,
so a half-copied link is the likely way to get one wrong; `/audit` had already held its own run-id
filter back for this reason, with a private regex. The regex is now `isUuid` in `core/domain.ts`,
and `syncQueryParams` takes a predicate as well as a list so the guard sits at the boundary the
untrusted value crosses. Deliberately **not** on the signal: what the server hands those screens is
always a whole id, and a screen that second-guessed its own response would be guarding the wrong
side — which is what the first attempt did, and five ingest specs said so.

**Channels was the last screen with a native `confirm()`.** Every other destructive action in the
console — deleting a video, re-running a phase — is arm-then-confirm in the page, for reasons the
videos list already documents: a dialog blocks the thread, takes focus out of the document and hands
it back to `<body>`. Removing a channel now arms its own button, which changes label rather than
being swapped out by an `@if`, and the consequence the dialog was carrying ("its discovered catalog
goes too; videos already ingested from it are kept") moves into the `role="status"` line where a
screen reader reaches it and where it is also on screen.

**Two screens fired actions with nothing announcing them.** Channels had no live region at all,
despite three mutations: add, sync and remove. A sync that discovers nothing new changes no cell on
the row, and a removed row simply leaves the table — both indistinguishable from a press that did
nothing. Run detail had the same gap on retry, where a queued retry takes the run back to PENDING
and the Retry button out of the head. Both take the `.said` line the videos list and the runs board
already share.

**Three list screens had no loading state.** Only videos said so; runs, channels and audit rendered
a blank panel body until the first response landed, and their comments justified it with "a
Loading… line here would sit there forever on an error" — which is true of an unguarded line and not
of the one videos actually ships. `isLoading() && !hasValue()` is false in the error state (so the
problem panel stays the whole answer) and false during a poll (so `reload()`'s held page is not
replaced by "Loading…" every two seconds). All four now carry it.

**Two header lines.** `/videos`' subtitle was missing `.prose`, so the one sentence on the screen ran
the full width of a 1440px grid while its three siblings capped at 72ch; `/runs` had no subtitle at
all, and it is the screen whose retry semantics most needed one sentence of explanation.

Left alone deliberately: **ingest renders its own rejects table** rather than `vk-rejects`, which the
same screen imports for its retry rejects and which channel detail uses for the identical
create-reject case. Both renderings are honest — the table has a caption and puts the URL through
`shortUrl`, the `<dl>` deliberately does not truncate — so this is a taste call, not a broken
standard. And **run detail stacks two `vk-problem`s** instead of the `actionFailure() ?? firstFailure(…)`
precedence every other screen follows; its own comment argues the case (a run that will not load and
a retry that was refused are two things to fix, and it has the room), so it stands.

## Related pages

- Hub: [VidIngest - Web UI](VidIngest%20-%20Web%20UI.md)
- How the app consumes these: [VidIngest - Web UI App Guide](VidIngest%20-%20Web%20UI%20App%20Guide.md)
- Endpoint list: [VidIngest - Config and Runtime](VidIngest%20-%20Config%20and%20Runtime.md)
