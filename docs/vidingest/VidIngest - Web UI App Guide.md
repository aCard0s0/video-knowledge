---
type: guide
last_reviewed: 2026-08-29
---

# VidIngest — Web UI app guide

How the console is built: the Angular shape, the signal traps it has already paid for, and the
shared primitives under `core/`. Split out of [VidIngest - Web UI](VidIngest%20-%20Web%20UI.md),
which is the hub for this page.

## The app

Angular 22, zoneless (no zone.js), standalone components, signals, `@if`/`@for`,
`provideHttpClient(withFetch())`, typed reactive forms, lazy-loaded routes. No component library:
the console is seven screens of tables, and a library's opinions would have to be overridden
everywhere. No GSAP — the only motion is a CSS breath on the live lane segment plus 150ms
hover/focus transitions, both off under `prefers-reduced-motion`.

**`resource.value()` throws once the resource is in its error state.** Angular raises
`ResourceValueError`, so `@if (r.value(); as v) { … } @else if (r.error()) { … }` never reaches
the error branch: the guard itself throws and the screen sits on its loading text forever with
the console filling once per poll. Check `error()` **first**, and route every other read through
`hasValue()`. The run screen shipped with the wrong order and a 404 run showed "Loading run…"
indefinitely; the other screens survived only because their error panel sits outside the guard.
There is no `@else if` in Angular that runs after a throwing `@if`.

**The router scrolls to top on every `syncQueryParams` write.** `provideRouter` is configured
with `withInMemoryScrolling({ scrollPositionRestoration: 'top' })`, and putting a filter in the
URL is a navigation — `replaceUrl` included. So any attempt to scroll the page from a handler that
also changes a URL-backed signal is undone a moment later, whichever hook it runs in
(`afterNextRender` and a deferred macrotask were both measured losing the race). Screens that need
something to stay in view lay it out that way instead of scrolling to it: the run screen caps its
item list past five items so the trail is never more than a screen down.

**`linkedSignal(() => …)` resets whenever anything it reads changes identity.** A selection
seeded from `items()` is thrown away on every poll, because the resource hands back a fresh
array each time. Either give it an explicit `source` that is stable (the route id), or keep the
manual pick in a plain signal and resolve the default in a `computed`.

```
applications/webapp/
  openapi/vidingest.json          snapshot of the live spec (input to codegen)
  src/app/api/generated/          openapi-generator output — 11 services, 42 models, never edited
  src/app/core/                   domain.ts (mirrored enums) · time.ts (UTC parsing, durations)
                                  lane.ts (+ spec) · problem.ts (ProblemDetail, firstFailure)
                                  audit.ts (tail paging, + spec) · paging.ts (clampPage, + spec)
                                  action.ts (busy/armed/said/failure, + spec)
                                  verdict.ts (a 2xx is not an acceptance, + spec)
                                  watch-run.ts (run + audit + lanes + ?run=, shared by 3 screens)
                                  poller.ts (the shared clock, and every age off it)
                                  url-state.ts · api-base.ts
  src/app/ui/                     lane · run-watch (+spec) · problem · fault · rejects · empty
                                  pager · phase-picker · status-badge
  src/app/features/               ingest (+spec) · channels (+detail) · runs (+detail) · videos (+detail) · audit
  src/styles/_tokens.scss         the corrected palette (this file's tokens win over MASTER.md)
```

Commands (from `../../applications/webapp/`):

```bash
npm start                 # ng serve on :4200, proxying /vidingest to :8051
npm run build             # production bundle into dist/webapp
npm test                  # vitest, no watch
npm run api:gen           # re-fetch the live spec, then regenerate the client
```

`npm run api:gen` needs the server up. It writes `openapi/vidingest.json` and regenerates
`src/app/api/generated/` — commit both together, and never hand-edit the generated tree.

### Deployment

`applications/webapp/Dockerfile` builds the bundle in a `node:24-alpine` stage and serves it from
nginx (`nginxinc/nginx-unprivileged`), published as the `webapp` compose service on `WEBAPP_PORT`
(8052). The build context is `applications/webapp` rather than the repo root, so the image cache
invalidates only when the console changes — which needs its own `.dockerignore`, since the root one
does not apply to a narrower context.

Until 2026-08-29 the console was instead built inside the server image and baked into the jar's
`classpath:/static`, served by a `SpaStaticResourceConfig`. Both are gone: there is one console in
one place, and `http://localhost:8051/vidingest/` no longer serves a page.

Four details make it work:

- The build uses `--base-href=/vidingest/`, because the app is served under that path and a build
  with the default `/` resolves every asset to a 404. Dev keeps `/`, since `ng serve` serves at the
  root and proxies `/vidingest` to the API.
- **nginx proxies the API rather than the app knowing where the server is.** Every request the
  console makes is relative and the server has no CORS configuration, so the bundle and the API
  have to be same-origin. The proxied prefixes — `api/`, `actuator`, `v3/`, `swagger-ui` — are the
  server's whole surface, and now live only in `nginx.conf`: a path that should reach the server
  but matches none of them falls into the SPA branch and returns `index.html` with a **200**, which
  reads as a blank screen rather than a 404.
- `try_files $uri $uri/ /vidingest/index.html` is what makes `/vidingest/runs?status=FAILED` survive
  a refresh or a shared link. `root` plus a `/vidingest` subdirectory rather than `alias`, because
  `alias` combined with `try_files` resolves `$uri` against the wrong prefix.
- `absolute_redirect off`, or the bare-root `return 302` is expanded with the *listen* port and
  sends the browser to the container's internal 8080. The healthcheck likewise uses `127.0.0.1`,
  not `localhost`: inside the container that resolves to `::1` first while nginx listens on IPv4
  only, which left the container permanently unhealthy against a working server.

Verified through the container: deep links (`/vidingest/settings`, `/vidingest/runs/abc`) 200,
hashed assets 200 and `immutable` with gzip 350 KB → 116 KB, `index.html` `no-store`, the API
returning real data, and `/vidingest/api/v1/nope` still a 404 `application/problem+json` rather
than the SPA shell.

### Flow decisions

- **Ingest does not dead-end.** Submitting used to leave a count and a link; the accepted items
  now appear beside the form as live lanes, polled at the same cadence as the runs board, so
  paste → start → diagnose happens on one screen. With nothing started, the column lists the last
  five runs instead of standing empty. The screen carries **Retry run** too, because stopping at
  "diagnose" sent the operator to the run screen for the one press that fixes what they were
  already looking at. It sends **no request body**: an absent `skipPhases` means "reuse the run's
  own set", and the phase picker on this screen describes the *next* run, not the one that failed.
- **The run being watched is in the URL** (`/ingest?run=<id>`). It used to live only in the submit
  response, so a refresh — or Back, or a pasted link — dropped the run that had just been started
  and left the operator to find it again on the runs board. Nothing else on the screen is URL state:
  the textarea is a draft and the picker configures the next run, but the run in flight is the thing
  someone would want to reopen. With no `?run` the column falls back to the last five runs.
- **A ticked phase chip means the phase will run.** The picker reads `GET /pipelines/capabilities`
  (finding 17) and renders what the deployment has off as struck-through and unpickable, with the
  reason in text rather than in a `title` no touch device can reach — and it does the same for a
  phase whose upstream the operator just unticked. It injects the shared singleton rather than
  taking a list as an input, so channel detail and run detail draw the identical picker without
  each having to fetch and thread the answer through.
- **The recent-runs column says what happened, in words.** Status was a 3px coloured bar with
  `aria-hidden`, so five FAILED runs read as five unremarkable rows; `errorCode` and `error` are on
  every `RunSummary` and neither was rendered at all. Labels go through `shortUrl` (`core/url.ts`),
  because `.truncate` clips the tail at a fixed 34ch and a watch URL spends its first 32 characters
  on boilerplate — what got cut was the video id.
- **Nothing the operator pasted is deleted for them.** The client filters non-http(s) lines out of
  the request, and used to then overwrite the textarea with only the *server's* rejects — so a
  batch of 100 with three typos ran 97 and erased the three, with no record they had ever been
  pasted. Everything that did not start stays in the box, and the table under the form lists both
  kinds together with the reason each one gave. That table was unreachable before: the server can
  only reject blank, non-http and in-request duplicates, and the client filters all three.
  five runs instead of standing empty.
- **Runs triage is one click, and the board says why.** Status is a row of chips rather than a
  select, every chip carries its count — FAILED because that number is why the screen gets opened —
  and FAILED rows carry a Retry button so triage does not require a navigation first. `GET
  /pipelines` has no group-by, so a count is a one-row query, but only **three** of the five are:
  `PENDING` and `IN_PROGRESS` come off the live tables' own responses, since those are already
  queries for those statuses and a `PageResponse.total` counts the query rather than the page.
  Asking again was two extra requests on every 2s tick and one number with two sources, which could
  disagree on screen. `error` is on every `RunSummary`, so the reason renders in a full-width row
  under its run rather than one navigation away: fourteen rows all reading `UPSTREAM_TOOL_FAILURE`
  are told apart only by the message tail, so it gets the width to wrap rather than a clip that
  lands the ellipsis on the discriminating half. The retry's own answer is read too — see
  finding 14.
- **The count on the FAILED chip is one press to clear.** `Retry N failed` fires every FAILED run
  *on the page* — not the chip's total, which past 25 rows would reach runs nobody had looked at.
  The requests go out together and the server's `vidingest.ingestion.concurrency` semaphore decides
  how many actually run; each is caught on its own, so one run that stopped being FAILED between
  the page load and the press cannot abandon the rest.
- **The row label is the URL with its boilerplate off.** `youtube.com/watch?v=dQw4w9WgXcQ`, not
  `https://www.youtube.com/watch?v=…`: the label truncates at 34ch with the ellipsis on the tail,
  and a watch URL spends its first 32 characters saying nothing, so what got clipped was the video
  id. Anything `URL` cannot parse is shown as pasted.
- **Retry rides the right edge.** The grid is 792px wide and a phone is 390px, so the button the
  screen exists for sat 412px past the viewport and cost a horizontal scroll per row; the actions
  column is `position: sticky; right: 0` with a hairline, so what scrolls under it reads as covered
  rather than as mangled data. Inert at any width where the table already fits.
- **The whole identity cell is the link.** It used to be the eight hex characters alone — 62×17px
  in a 389px cell, under the 24×24 target minimum — with the label beside it a dead span, so the
  obvious thing to click did nothing. The id keeps the underline; the label stays `--fg-muted`.
- **A retry says what it did.** `Queued 3 of 4 runs.` in a `role="status"` line that is always in
  the DOM, so a screen reader has a region to announce into. Under the FAILED chip a successful
  retry takes the row out of the filter it was listed under, and a row silently disappearing is
  indistinguishable from a press that did nothing.
- **`videoCount` counts videos, so the column says Videos.** `RunSummaryPageService` counts the
  video rows attached to the run, not the URLs submitted — a run whose video was deleted reports
  `0`, and most COMPLETED runs on the dev box did. The header names what it is, and none renders as
  `—` rather than a `0` that reads like a failure.
- **The live panel never repeats the history.** Under ALL, every running run was in both tables, so
  the same id sat on screen twice. The panel lists the live runs whose ids are *not* on the history
  page — matched by id, so a live run on page 2 still shows — and says "Listed in the history below"
  when that leaves nothing. The count in the head stays either way, because it is status.
- **Sorting is the server's, and it is two columns.** `GET /pipelines?sortBy=createdAt|updatedAt`,
  whitelisted in `RunQueryService` because the value becomes a JPA property name. Sorting the 25
  rows in hand would have answered "what moved most recently" with "of the 25 oldest-created, which
  moved last" — a different question, and wrong in the way that looks right.
- **A phone gives up four columns.** PHASE (`—` for every terminal run), CHANNEL, VIDEOS and CREATED
  hide below 767px, leaving STATUS, RUN, UPDATED and Retry; the live panel keeps PHASE and STARTED,
  which are the two things it exists to show. The RUN cell wraps id over label rather than forcing
  them onto one line.
- **The video screen shows its dossier.** Transcription provider/language/character count,
  artifact counts and the file path fill the column under the player, all from the `/detail`
  response the screen was already fetching.
- **A knowledge filter that matches nothing says so, and does not offer the phase.** The pane
  branched only on "no units", so filtering to a type the video has none of rendered *No knowledge
  units for this video.* over a **Run KNOWLEDGE** button — while the tab beside it counted the units
  of every other type and the dossier agreed. It offered an LLM extraction over the whole video as
  the fix for a chip. The filtered branch names the type and offers **Show all types**; only the
  genuinely empty video is offered the phase. Same correction the videos list already carried.
- **A rerun the server will refuse is not offered — and that is one phase, not six.**
  `VideoPhaseRunnerService` deliberately skips `applies(ctx)`: the rerun row is the operator's
  escape hatch ("re-OCR after a paddleocr-server upgrade"), so a phase the deployment has switched
  off still runs. Measured against a server with DIARIZE, FRAME_SAMPLE, OCR and KNOWLEDGE off:
  FRAME_SAMPLE answered `200` with 17 rows, DIARIZE reached its sidecar (`502`), and only KNOWLEDGE
  answered `409` — `KnowledgeExtractionService` checks `vidingest.knowledge.enabled` itself, so the
  toggle cannot be forced past. So the screen reads the same `Capabilities` singleton the phase
  picker does (finding 17) but disables **only** KNOWLEDGE; greying out the rest would have broken
  the one thing the row exists for. The knowledge empty state names the flag rather than blaming a
  missing ollama model, which is not why that deployment has no units.
- **A rerun takes two presses, and says what it costs in text.** A chip wipes this video's
  artifacts for its phase before rebuilding them, so one stray click among seven cost a transcript
  and a ten-minute whisper call — with the only warning in a `title` attribute, unreachable by
  touch and by keyboard, the same trap the rail's health checks and the picker's reasons were
  pulled out of. Now: arm on the first press, send on the second, re-arm by pressing another chip,
  Cancel or Esc to back out — the shape the videos list already uses for delete. The empty-state
  CTAs inside the panes stay **one** press on purpose: an empty pane has nothing to wipe, and a
  confirm that guards nothing is the kind people learn to click through.
- **Knowledge units are sorted client-side by `startSeconds`.** The server's
  `findByVideo_IdOrderByCreatedAtAsc` is *insert* order — one batch writes its units in the order
  the model emitted them, all sharing a timestamp — and its javadoc calling that "timeline-ordered"
  is what made it look already solved. Rendered down a timecode gutter beside a player, the list
  walked backwards: 00:50, 01:15, 01:50, 00:00, 00:35. Sorted on the client rather than in the
  repository because the ordering is this screen's need and the MCP and CLI read the same endpoint;
  units with no `startSeconds` sort last, since they belong to no moment.
- **A rename writes the server's answer into its own row, and never refetches the list.** Rows bind
  `[value]` with no `(input)` and `track speaker.id` keeps the DOM node, so the `speakers.reload()`
  that used to follow a save re-evaluated that binding on *every* row — renaming the first of two
  speakers silently discarded the name typed into the second. `PATCH /speakers/{id}` answers with
  the updated `SpeakerDto`, so the one changed row is written in place. A `role="status"` line says
  what was saved: a row that goes back to looking exactly as it did is indistinguishable from a
  press that did nothing.
- **The dossier carries the timestamps `/detail` was already returning.** `video.createdAt` as
  `ingested`, and the transcription's `updatedAt` as `written` — the videos *list* showed an age the
  one screen that could explain a video did not, so "is this transcript older than the model change"
  had no answer. Both go through `humanAge`/`absoluteTime`, so a zoneless timestamp still reads as
  the server bug it is.
- **A video links back to its channel.** `GET /videos` already filters on `channelName` and the
  videos screen already reads `?channel=`; only the link was missing, so the trip from a video to
  the rest of its channel was Back and retype. Guarded on a blank name, which the corpus has plenty
  of — those still render `no channel`, not an empty link.
- **The fused row names its speakers instead of counting them.** `speakerLabels` was rendered as
  `2 speaker(s)`; naming them is what DIARIZE is for, and it is the same natural key the speakers
  pane and `multimodal_segments` both use. Its `ocrText` is clamped to three lines — paddleocr
  returns every string on the frame, so one screen-share segment ran to fifteen lines of sidebar
  chrome in the warn colour, louder than the transcript it annotates. The full read is per frame in
  the OCR pane, which is where it is legible anyway.
- **An OCR thumbnail reserves the box it actually gets.** `width="160" height="90"` with
  `height: auto` let the real frame decide, so a 9:16 upload rendered 160×299 and the reserved box
  tripled on load, once per frame. The box is now 4:3 with `object-fit: contain` — `contain` because
  the pane exists to check a read against the frame it came from and cropping hides the part of the
  screen the text was on, and 4:3 rather than 16:9 because a portrait frame letterboxed into 16:9 is
  51px wide.
- **`0` is a value, not an absence.** `line.confidence` and `fullTextChars` were guarded by
  truthiness, so the OCR line read at 0% confidence — the one most worth flagging — rendered as if
  it had no score, and an empty transcript looked like a missing field.
- **Below 1100px the player pins to the top and the list scrolls under it.** One column meant the
  panel could no longer ride beside the list, so the transcript became a ~6000px page whose taps
  moved a player a full screen above the viewport: audio started and nothing on screen said where it
  had gone. `position: sticky` on the video alone does not fix it — a sticky element is bounded by
  its containing block, and the panel ends long before the list does (measured at -701px with the
  list still going). `.player-panel` takes `display: contents` at that width, promoting its children
  to grid items of `.split`, which spans the artifacts too. The rows carry a matching
  `scroll-margin-top`, because a sticky element must not cover what focus just moved to.
  **No `order` on those children**: `order` moves only the paint, so focus would leave the player,
  skip the whole list and land on a chip painted underneath it.
- **A synchronous rerun reports its elapsed time into a live region.** OCR over seventeen frames
  measured past ten minutes (individual frames 2s–120s, several timing out), and the only feedback
  for the whole of it was a 10px `OCR…` in a 20px chip: the `role="status"` line was empty, and
  `:empty { display: none }` had taken it out of the accessibility tree — a live region inserted at
  the moment it has something to say is not reliably announced. The line is now always present and
  carries `PHASE running — 1m 12s elapsed`, on its own interval rather than `poller.now()`, which
  stops when the operator pauses polling while the request does not (the reason the rail's wall
  clock has one). The chips also went from 20px to a 24px minimum target.
- **`.truncate` needs a box, and a `<td>` is one where an inline `<a>` is not.** `max-width` does
  not apply to a non-replaced inline box, so the videos list's title anchor rendered its full 293px
  and its `text-overflow: ellipsis` could never fire. The grid ran 131px past its panel at 1440px
  and 882px at 390px, which put Delete 52px and 804px past the viewport respectively — and squeezed
  the 3px status spine to **0px**, since `width` on a table cell is only a suggestion once the table
  is wider than its container. So the one screen with fifty rows to sweep was the one with no spine,
  while runs and channels (whose tables fit) rendered theirs fine. The class moves to the cell,
  `td.spine`/`th.sp` carry `min-width` as well as `width`, and the same latent bug still sits on
  `channels.html`'s url anchor.
- **The videos list gives up two columns and pins Delete**, the corrections the runs board already
  carried for the identical table shape. (The pin itself now lives in `styles.scss` as
  `.stick` / `.grid-pinned` — see finding 20.) CHANNEL is the filter's own value repeated down the page —
  and the widest cell on the screen, a channel name having no length limit — and SOURCE is an
  extractor plus an id that video detail shows in full; both go below 767px, both come back above
  it. Two things the port taught that the runs board had not had to state: restating
  `tbody tr { background }` for the sticky cell to inherit **silently kills the global
  `tr:hover` wash** (equal specificity, and component styles are injected last), and the pinned
  column **covered the focus ring** on a short title at 390px — the focused link is lifted over it
  rather than left 15px short of a closed rectangle.
- **`.truncate` keeps its single line below 767px on this grid only.** `table.grid td` sets
  `white-space: normal` there and outranks a bare `.truncate` (0,1,2 against 0,1,0), so a cell that
  opted into truncation *wrapped* at the 18ch cap instead of clipping at it: a 63-character channel
  name became five lines and took its row to 114px next to rows of 36px. Fixed in
  `features/videos/videos.scss`, deliberately **not** in `styles.scss` — ingest's rejected URLs and
  the channel catalog's titles are fully readable on a phone *because* of that wrap, and a global
  fix trades two phone layouts for one.
- **A search box has to match a fragment.** `?channelName=` was `cb.equal`, so typing `comp` into a
  box labelled "Channel name, e.g. LuxAlgo…" returned nothing while nine Computerphile videos sat in
  the table under "No videos match this filter". `VideoSpecifications.hasChannelName` is now a
  case-insensitive `LIKE %…%`; channel detail's `Ingested videos →` link still matches, as a
  substring of itself. `source` stays exact — it is a yt-dlp extractor id, not something anyone
  types a fragment of. The cost is that `idx_vidingest_videos_channel_name` can no longer serve the
  predicate: a leading wildcard defeats the btree, and the index is left in place rather than
  dropped in the same change.
- **Which emptiness it is decides the way out.** "No videos match this filter." over an unfiltered
  empty corpus, with `Ingest a URL →` as the only action, pointed someone who had merely mistyped a
  channel name at the one thing that could not help them. Filtered says so and offers **Clear
  filters**; unfiltered reads "No videos ingested yet." and keeps the ingest link. Same defect the
  channel catalog had when it reported "Every upload is already ingested" over a 404ed sync, and
  `empty-state.spec.ts` now pins both branches.
- **A FAILED video no longer dead-ends.** Why it failed lives on the run (`error`, `errorCode`) and
  on its item (`failedPhase`), none of which is on a `VideoSummary` — but `pipelineId` is, and was
  rendered nowhere, so six FAILED rows had nothing to click. A `Run` column carries the id as a link
  and `—` where the run row is gone (`ON DELETE SET NULL` makes that `""`). It fits at 1440px because
  the title and channel caps leave slack, and it is `hide-sm` like the two columns beside it.
- **A two-press delete has to keep its focus.** The confirm used to be a *different* button swapped
  in by an `@if`, so Angular destroyed the one the operator was standing on and focus fell to
  `<body>` — the confirm they had just asked for was a whole tab cycle away. One button that changes
  its label instead, and it stays **enabled** while the request is in flight (`disabled` removes an
  element from the tab order, which was the same bug one press later) with `remove()` ignoring the
  repeat press. On success the focus moves to the neighbouring row's button before the deleted row
  is taken away, so deleting three videos is three presses rather than three trips in from the top.
- **The screen says what the press did.** There was no live region anywhere on it: a deleted row
  simply left the table, which is indistinguishable from a press that did nothing. A `role="status"`
  line, always in the DOM so it is a region to announce into rather than one that appears already
  spoken, reads `Press Confirm delete to remove <title>.` and then `Deleted <title>.` — the same
  shape the runs board gives a retry — literally, now: `.said` is one class in `styles.scss` rather
  than the same four declarations under two names. The *state* behind it is shared too, since
  Sep 2026: `actionState()` in `core/action.ts` holds the `busy`/`armed`/`said`/`failure` four that
  six screens had written by hand, keyed so one instance covers a list.
- **Videos triage is one press too, and the count is on the chip.** A `<select>` of nine statuses
  put the screen's whole reason for existing two clicks away and showed no number; the row of chips
  is the runs board's, with `FAILED` carrying its total from one extra one-row query. Nine chips wrap
  to five lines at 390px, which is the price of one-press triage on a phone — `FAILED` is last in
  pipeline order, so it lands nearest the table. `.chips` / `.chip` / `.chip .count` moved out of
  `runs.scss` into `styles.scss` when the second screen grew them.
- **A query param is whatever someone pasted.** `?status=BOGUS` went straight into the signal, out to
  `GET /videos`, and came back as `No enum constant …VideoStatus.BOGUS` — under a control that showed
  `ALL`, because nothing matched. `syncQueryParams` takes an optional per-key guard — a list, or a
  predicate for a shape no list can spell; a rejected value is ignored, so the signal keeps its
  declared default and the write effect drops the key from the URL, the same self-healing
  `clampPage` gives a page past the end. Passed on every screen whose params reach a server-side
  parse — `/videos` and `/runs` (`status`, plus `sortBy`, which the server *silently* falls back
  on), `/audit` (`eventType`, `status`, `phase`, `errorCode`), `/videos/{id}` (`pane`, `type`) and
  the two `?run=` screens (`isUuid`). See finding 20 for the last three.
- **The × on a search box has to re-query.** `<input type="search">` fires `input` and `search` when
  its native clear button is pressed and defers `change` to blur, so a `(change)`-only handler left
  the box empty while the filtered rows and `?channel=` stayed — the control contradicting its own
  screen. Both search inputs in the console (`/videos` channel, `/audit` runId) now carry `(search)`
  alongside `(change)`. No debounce, because neither fires per keystroke.
- **A panel head wraps rather than pushing its last child off the screen.** `space-between` plus a
  filter group that cannot shrink put "64 total" at a right edge of 406px in a 390px viewport — a
  horizontal scrollbar on the *document*, not on the table that has one by design. `flex-wrap` on the
  shared `.panel-head`, and the channel input went from `width: 22ch` to `flex: 0 1 28ch` so its
  placeholder stops being cut to "Channel name, e.g. Lu" — losing the example is losing the half that
  says what the box matches.
- **The channel column is drawn only when it distinguishes the rows.** Under a channel filter every
  row repeats the value already in the box above, and it is the widest column on the screen. Both
  halves of the condition matter: the filter is a *substring* match, so an active filter no longer
  implies one channel, and an unfiltered page that happens to hold one keeps its column rather than
  dropping it on page 1 and growing it back on page 2.
- **Small controls get 24px.** `.btn-sm` was 4px of padding around 13px type — a 22px control, and
  every small button in the console is one (Delete 53×22, the pager 43×22, a chip 20px). The title
  link was worse at 293×**15**px, and `min-height` on an inline box does nothing: `display: block`
  on the anchor is what gives it a target *and* what makes `.truncate`'s `max-width` apply, so that
  one line replaced the cell-level workaround the first pass had used.
- **The videos list polls, because half its statuses are transitional.** It fetched once and never
  again: a row left TRANSCRIBING stayed TRANSCRIBING for as long as the screen was open, under a
  rail saying "updated 2.0s ago" and ages that ticked — the `Poller` was injected only to move those
  labels. Six of the twenty-five rows on a fresh page 1 were non-terminal. Cadence is `POLL_LIVE`
  while any row on *this page* is neither COMPLETED nor FAILED and `POLL_IDLE` otherwise, the same
  question the runs board asks of its own rows; the set names what is *finished*, so a status the
  server adds counts as moving rather than frozen.
- **Theme is the operator's, and dark is still the default the console was drawn for.** The rail
  foot toggles light/dark beside Collapse; with no stored choice the tokens follow the OS with no
  JavaScript. See "The light theme" below for the measured ramp and the three-step resolution.
- **The shell is one nav shape at every width.** The rail carries an inline SVG per section and
  collapses to `--nav-w-collapsed` (56px, icons only, state in `localStorage`); below 900px it is
  that rail, because the old wrapping row of labels read as leftovers. The labels drop out on a
  **container query** over the rail's own width, so the toggle and the breakpoint reach them by
  one route, and they stay in the DOM as screen-reader text — that is what names an icon-only link.
- **One column of chrome, not two.** There is no top strip: the rail carries the name, the
  sections, where you are inside the active one, and — in its foot — the dependency checks, the
  poll age and the pause switch. It is sticky and full height, so none of that scrolls away, and
  the content area gets the 28px back. What survives the collapse to 56px is every number and
  every control; only the words go.
- **The rail reads as the menu it is.** Three tiers of brand (mark, `pipeline // ops` descriptor,
  `vidingest_` wordmark with a caret), then ordinals — `01`…`05` — beside each section's icon and
  label, the active one taking an accent wash, an accent bar and an accent ordinal. The label
  stays `--fg`: the palette reserves accent *text* for the primary CTA. Ordinals are positional
  (`$index`), never stored per section, so reordering cannot desync them.
- **The foot ends with a UTC clock.** Every timestamp the server sends is naive UTC (finding 5),
  so the console says which clock it is showing, once, instead of each screen implying it. It runs
  on its own interval rather than `poller.now()`, which stops while polling is paused — right for
  a live lane segment, wrong for a wall clock. The theme switch shares its row: both are ambient,
  neither is status.
- **The foot says what is broken and how stale this is.** The `/health/ready` checks and
  `/health/llm` merge into one list: the count reads `1 down` the moment anything is (never
  `3 ok` while `videoPath` is not writable), the failing checks are named beside it while the rail
  is wide, and a native `popover` — light dismiss and Esc for free — lists every check with the
  value the server gave it. Those values used to live in a `title` no touch device and no keyboard
  could reach. The popover lives *inside* the rail so it can read `--nav-now` and open flush
  against whatever width the rail has; the top layer ignores DOM position and `overflow: hidden`
  alike.
- **The id rides its own section.** `crumb()` derives it from the URL and it renders indented under
  the active nav item, so a run id sits under Runs rather than in a breadcrumb repeating the word
  the active item already shows. No screen has to publish a title to the shell.
- **Adding a channel syncs it.** A new channel used to sit `NEW` with an empty catalog until the
  operator noticed the Sync button or the half-hour scheduler ran, which made Add look inert.
- **A link out to what the channel produced.** `GET /videos` already filters on `channelName` and
  the videos screen already reads `?channel=`; only the link from the channel was missing.
- **A channel can be removed.** Add was one-way, and there was no disable, so a mistyped URL stayed
  `ERROR` while the scheduler re-ran yt-dlp against it forever (finding 16). Remove sits beside Sync on the list,
  behind a confirm that says what goes — the catalog — and what does not: the videos already
  ingested from it.
- **Starting a channel batch does not dead-end either.** It used to leave `Run started for N
  video(s)` and a link, which is the dead end ingest had already been given lanes to fix — and the
  N was `items.length`, so videos the server *refused* were counted as started. The panel now reads
  `1 accepted · 1 rejected`, watches the run in place through the same `core/watch-run.ts` that
  feeds ingest and run detail — and now through the same panel, `ui/run-watch.ts`, which is where
  the `?run=` param this screen was copied without ended up — and hands the refusals to
  `vk-rejects`. Warn rather than the failure
  ramp, and labelled `not started` rather than `not retried`: a video declined here is not
  something the operator can fix, because the URL came from the stored catalog and not from a box
  they typed into. **An ingest that starts nothing still shows why** (Sep 2026): when every picked
  video is refused the server creates no run, so `runId` stays empty — and the panel was keyed on
  that id, which hid the whole thing including the table naming each refusal. The reasons reached
  the client and were rendered nowhere. `watching()` counts `started()` too, and the panel drops its
  `full run →` link when there is no run to open.
- **The channel screen is one screen wide.** The ingest CTA sat ~1000px below the fold on a
  fifty-row page — tick a box at the top, scroll a full screen to press it, with the selection
  count out of sight the whole time it was being built — so the panel is `position: sticky` against
  the bottom edge. The picking target went with it: the title cell is the checkbox's `<label>`,
  because a 13×13px box in a 32px row was the only way to choose a video, fifty times over.
- **Columns that say nothing are not drawn.** `Published` is empty for a whole catalog (finding
  15), so it appears only when a row on the page actually carries a date; `State` reads `new` on
  every row while "not ingested only" is ticked, so it appears only when the filter is off. The
  catalog count says `(newest 200 only)` when it has hit `channelSyncLimit`, because a full
  catalog is a window and not the channel's size.
- **A failure gets a row, not a column.** The channel's `lastError` was clipped to a line — about
  three quarters of it hidden behind a `title` no touch device can reach, the arrangement the
  readiness checks were moved out of — and wrapping it in place made a twenty-line tower in a 14ch
  column at 390px. It now takes a full-width row under its channel, the shape the runs board
  already gives a run's error, capped at the same 92ch. **The shared rule needs
  `table.grid tr.fault-row td`**: `table.grid td` sets `white-space: nowrap` at (0,1,2) and a bare
  `.fault-row td` loses to it. Component styles hide that — Angular's scoping attribute lends the
  same selector a point it does not have in `styles.scss`.
- **An empty catalog says it is empty.** The empty state branched on the "not ingested only"
  filter, which defaults on, so a channel whose sync had just failed with a yt-dlp 404 reported
  `Every upload in this catalog is already ingested` — directly under the red panel naming the 404.
  Nothing on the page with the filter off means no uploads, not that they all ran.
- **The audit feed's When column is a clock, not an age.** It is the same table the run screen's
  trail is, one row per event, and a relative age cannot order what it lists: page 7 of the dev
  feed was nine transitions of one item all reading `43h 29m ago`, and the two that matter are 3ms
  apart. `clockTime` (`core/time.ts`) exists for exactly this and the trail already used it — this
  screen was the one still on `humanAge`. It also cost three lines of wrap per row at 390px, since
  `43h 29m ago` is the only value in the table with spaces to break on.
- **The day is rendered where it changes, not on every row.** A clock with no date is ambiguous
  across a midnight, and this feed spans days where the trail spans minutes. Two rows on a
  three-day page instead of eight characters on all fifty. `dayLabel` (`core/time.ts`) is the whole
  mechanism: the row compares its own label with the previous row's
  (`dayLabel(rows()[$index - 1]?.occurredAt)`, where a negative index reads `undefined` and so heads
  row 0), because the label **doubles as the grouping key** — same day, same string — and nothing
  else then defines "same day". It is *local*, matching the clock beside it rather than the rail's
  UTC wall clock, which is the property `audit.spec.ts` pins across a UTC midnight. A `Map` of
  marked event ids built in the component said the same thing in 60 more lines.
- **The day heading spans the spine column.** One `colspan="8"` cell, no `<td class="spine">`: that
  cell would carry no status and take the heading's padding, and a padded cell in the 3px spine
  column widens it for every row in the table — measured 3px → 24px. It is also why the heading sits
  flush with the panel edge rather than aligned to the When column.
- **A failure gets a row here too.** The feed carried `errorCode` and `error` in a `min-width: 28ch`
  Detail column — the arrangement the runs board and the channel list were both moved out of, for
  the reason the trail's own comment gives: 325 of 359 events have nothing to say there and paid the
  width anyway, while the 34 that do had their tail pushed off a phone entirely. Now the shared
  `fault-row`, which is also what themes and wraps it for free.
- **A CANCELLED event is not a failure.** `vk-fault` takes `[cancelled]` precisely because a
  cancelled item carries an `errorCode` like any other (`DUPLICATE_VIDEO`), and the feed was the one
  screen not passing it — so one row said `CANCELLED` in the calm ramp and `DUPLICATE_VIDEO` in red,
  about the same event.
- **`previousPhase` and `itemId` were on every event and rendered nowhere.** The trail gives
  `previousPhase` a `From` column; the feed dropped it, which on `ITEM_RETRY_REQUESTED` left the row
  naming **no** phase at all — that event's own `phase` is `""` and `previousPhase` is the only one
  it carries. `itemId` now rides the run link as `?item=`, the deep link run detail already honours,
  so a row on a 100-URL run opens the item it belongs to instead of a list to search. No `?phase=`:
  that filters the trail, and a row here is one event, not a request to see only that step.
- **`""` is absent in the Phase column too.** The trail renders `{{ event.phase || '—' }}` with a
  comment naming `ITEM_RETRY_REQUESTED`; the feed rendered it bare, so seven rows had an empty cell
  where the rule (finding 6) says a dash goes.
- **The run-id filter takes what the screen shows, or says so.** `?runId` is a `UUID` parameter and
  the Run column prints `runId.slice(0, 8)`, so the eight characters an operator copies off a row
  answered `400 Failed to convert … to java.util.UUID` with the table replaced by the panel
  reporting it — and the placeholder `710a9419…` *demonstrated* the rejected form. The placeholder is
  a whole uuid now, the id itself is on every Run link's `title`, and a value that is not a uuid
  holds the request back behind a hint instead of spending a round trip to be told no.
  `aria-describedby` is unconditional: pointing at an element that is not in the DOM yet is what
  leaves the hint unannounced when it appears.
- **Phase and error code are filters, server-side.** `eventType=ITEM_FAILED` answers "what broke"
  and left the next question — which of these 26 are `TRANSCRIPTION_FAILURE` — to the operator's
  eyes. `?phase` and `?errorCode` mirror the existing enum-filter shape exactly, including that an
  unrecognised value matches **nothing** rather than 400ing: the parameter is a `String` so a stale
  link cannot fail the request, which is the same reason `?sortBy` falls back rather than throwing
  (finding 14). The phase list offers `CREATED` and `DONE` — never lane *steps*, but `ITEM_CREATED`
  and `ITEM_COMPLETED` carry them, so filtering to one is a real question on this screen and only
  this one. `PipelineAuditFilterIntegrationTest` pins both contracts against a real database,
  because the service's unit test mocks the repository and so never sees inside the `Specification`.
- **The count is announced, not just rendered.** Changing a filter refetches 50 rows and moves a
  number in the pager, which says nothing to a screen reader; six other screens already carry a
  `role="status"` line and this one did not. It is `sr-only` because the pager already says it on
  screen, and always in the DOM so there is a region to announce into.
- **A phone gives up From and Attempt — and keeps Status.** The trail drops Status at this width too,
  but here the only other thing carrying it is the 3px spine, and colour alone is not a state in this
  app: `vk-status` renders the token *as* the label for that reason, and nothing else names
  `IN_PROGRESS` on an `ITEM_PHASE_ENTERED` row. Reaching Run past it costs a short sideways drag,
  which the fault no longer does now it has its own full-width row.

### The phase lane

The one visualization: per run item, the ten phases as a horizontal track whose segment widths
are proportional to measured duration (`core/lane.ts`, unit-tested in `core/lane.spec.ts`). Both
screens that draw lanes — run detail, ingest and channel detail — get the run, the audit tail and
the built lanes from `core/watch-run.ts`, so a correction to either fetch lands once. The two that
start a run draw one panel around it, `ui/run-watch.ts`: head, meta line and one row per item, with
each screen supplying only its head label and whatever can be done to the run.
Skipped phases render as hatched voids and stay individually visible (which ones were turned off
is information); consecutive *unreached* phases collapse into a single void carrying their count,
because an item that dies in METADATA otherwise renders as nine identical empty boxes. The failed
phase takes a red cap, and the live one keeps growing against the poll clock. Clicking a segment filters the audit
trail to that phase — on ingest, which has no trail of its own, that opens the run screen with
`?item=…&phase=…` already applied; unwired, the segments were focusable buttons that did nothing,
ten per completed item. `CREATED` and `DONE` never appear — they are run markers.

**`[status]` is not optional on `<vk-lane>`.** It is the input for the one outcome no segment can
express: an item reaped while still queued blames `CREATED`, so every segment reads `pending` and
the track itself has to carry the failure. Without it `dead()` is permanently false and the lane
labels a dead item `complete` — which is finding 3's blank-boxes bug, one binding over. The ingest
screen shipped without it while run detail had it, so the same run read differently on the two
screens that draw it; `features/ingest/ingest.spec.ts` now pins the `CREATED` case.

### Deliberate deviations from the Web Interface Guidelines

| Rule | What this app does, and why |
|---|---|
| Title Case for headings and buttons | Screen titles are lowercase mono (`runs`, `audit`, `run 710a9419`) so they read as the command that produced them; buttons stay sentence case |
| Submit stays enabled until the request starts | Ingest and channel-ingest disable submit at zero selection — there is nothing to send, and the count line above says so |
| Meaningful media needs captions | The transcript pane sits beside the player and seeks it; the API exposes no WebVTT to attach as a `<track>` |
| Critical fonts preloaded | Fira is self-hosted via `@fontsource` with hashed filenames, so there is no stable href to preload; `font-display: swap` still applies |
| Dates and durations via `Intl.*` | Ages are `humanDuration` (`4s ago`), not `Intl.RelativeTimeFormat` (`4 seconds ago`) — the gutter is 28px of 11px mono, and the runs board compares hundreds of them |
| Deep-link all stateful UI | The rail's collapsed state is `localStorage`, not a query param: it describes the operator's chrome, not the screen, and putting it in the URL would ship it in every link they paste |

A screen's filters, tab and page live in the query string (`core/url-state.ts`), and **a default
never does** — a shared link carries only what was actually chosen. A screen whose default is a real
value declares it: `syncQueryParams({ … }, { sortBy: 'createdAt' })`. That second argument is not
optional decoration. The rule used to be a hardcoded `'' | 'ALL' | 0 | false`, which is a guess
about what a default looks like, and it was wrong for three of the six screens — `/runs`,
`/videos/{id}` and `/channels/{id}` each wrote `?sortBy=createdAt`, `?pane=transcript` or
`?onlyNew=true` on load with nothing chosen. A declared default is also **exhaustive for its key**,
which is the half that cannot be merged with the empty-ish rule: `onlyNew` starts `true`, so `false`
is the operator's choice, and the generic rule would have dropped precisely the value the link
existed to carry.

## Related pages

- Hub: [VidIngest - Web UI](VidIngest%20-%20Web%20UI.md)
- What the API actually returns: [VidIngest - Web UI API Findings](VidIngest%20-%20Web%20UI%20API%20Findings.md)
- What a change hits: [docs/map/effects](../map/effects/CONTEXT.md)
