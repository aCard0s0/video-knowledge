# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

Build (Java 26 + Maven 3.9+ enforced by the root POM; `./mvnw` pins the build):

```bash
./mvnw clean package
```

Build one module plus its dependencies:

```bash
./mvnw -pl applications/vidingest/vidingest-server -am clean package
```

Tests. `test.groups` is empty by default, so `mvn test` runs **everything, including
Testcontainers integration tests** — those start a `pgvector/pgvector:pg17` container and
need a running Docker daemon.

```bash
./mvnw test
```

Running one test with `-am` fails the build on the sibling modules that have no match
("No tests matching pattern"), so pair `-Dtest=` with the opt-out:

```bash
./mvnw -pl applications/vidingest/vidingest-server -am test -Dtest=FusePhaseTest -Dsurefire.failIfNoSpecifiedTests=false
```

```bash
./mvnw -pl applications/vidingest/vidingest-server test -Dtest='*IntegrationTest'
```

**Always pair `clean` with `-am`.** `./mvnw -pl <module> clean test` resolves the sibling
`vidingest-*` modules from `~/.m2` instead of the reactor, so a stale installed jar makes the
build compile against yesterday's API. That fails as **BUILD SUCCESS** over source that does not
compile — incremental `test-compile` skips work it thinks is up to date, and the errors only
appear once something forces a real recompile.

Run the stack (`scripts/tradey.sh --help` for the full command/target list; infra starts
automatically as a dependency of the server):

```bash
./scripts/tradey.sh start --build
```

```bash
./scripts/tradey.sh logs -f vidingest
```

```bash
./scripts/tradey.sh cli
```

Optional footprints: `start sidecars` (paddleocr-server, diarize-asr), `start mcp`.
`down --volumes` wipes data. Host ports live in [compose/ports.env](compose/ports.env);
everything binds `127.0.0.1` (`VK_BIND_ADDR`). `scripts/compose.sh` is the raw
`docker compose` escape hatch with the same file layering.

Docs upkeep: `python3 scripts/check-markdown-links.py`,
`./scripts/regenerate-mermaid-svgs.sh` after editing `docs/**/diagrams/mermaid/*.mmd`.

## Architecture

Maven reactor: `libraries` (7 `common-*` modules) + `applications/vidingest` (5). Everything is
`com.tradinglabs`; groupId and DB defaults (`tradingPlatformDB`, user `dealer`) are
leftovers from the trading-platform repo this was carved out of.

`libraries/common-*` are vendored shared libs (logging, web error handling, HTTP client
core, observability, operation logging, MCP configs). They are dependencies of the
vidingest modules, not app code — change them only when the shared behavior is wrong.

Module roles and the dependency direction that matters:

- `vidingest-api` — DTOs + `VidIngestApiPaths` (single source of truth for every REST
  path). Server controllers and `vidingest-client` both reference these constants; add a
  path there, never inline a literal.
- `vidingest-server` — REST + the ingestion pipeline + JPA/Liquibase/pgvector.
- `vidingest-client` — typed HTTP client over the server, auto-configured via
  `vidingest.server.*`.
- `vidingest-mcp` — separate Spring Boot app, MCP SSE transport, tools delegate to the
  server through `vidingest-client` (no direct DB access).
- `vidingest-cli` — Spring Shell console, `web-application-type=none`, also talks HTTP.

### Pipeline

`pipeline/service/phase/` is the core. `PipelinePhase` is a **sealed interface** whose
`permits` clause lists every phase; `PipelinePhaseRegistry` fixes the execution order by
constructor injection:

```
METADATA → DOWNLOAD → PERSIST → TRANSCRIBE → DIARIZE → FRAME_SAMPLE → OCR → FUSE → KNOWLEDGE → CONTEXT
```

Adding a phase means touching five: the `PipelineRunPhase` enum (add the constant, and
list it in `isOptional()` if it can be skipped), the new `XPhase` class, the sealed
`permits` list, the registry constructor, and a config class for its
`vidingest.<phase>.enabled` toggle. Nothing else — the per-run opt-out is a
`Set<PipelineRunPhase>`, so the REST records, MCP tools and CLI options do not change per
phase. That used to be six positional booleans threaded through 15 files.

Each phase gates itself via `applies(ctx)`. The default implementation on `PipelinePhase`
is `!ctx.skipped(phase())`, so a phase overrides it only to add a
`vidingest.<phase>.enabled` check or an upstream dependency (OCR needs FRAME_SAMPLE,
DIARIZE needs TRANSCRIBE). Most of those properties default to `false`: the enrichment
phases are opt-in per deployment, so a default local run only does
metadata → download → persist → transcribe → fuse → context.

`PipelineRunPhase.isOptional()` is the single answer to both "can a run skip this?" and
"can the rerun endpoint re-execute this?" — the same set either way, because an optional
phase consumes the persisted video row while METADATA/DOWNLOAD/PERSIST consume the URL.
`SkipPhasesParser` turns the wire strings into the enum and 400s on anything mandatory or
unknown; the API module cannot import the enum, since the server depends on the API and
not the reverse.

Run state is split across services on purpose: `PipelineService` orchestrates and submits
to `vidingestIngestionExecutor`, `RunLifecycleService`/`RunItemLifecycleService` own the
`PipelineRun`/`PipelineRunItem` rows (`RunLifecycleService` creates and resets runs but
writes no status — every `PipelineRun` status write goes through `RunAggregationService`),
`RunAggregationService` rolls item status up to run status, `PipelineAuditService` records `PipelineRunItemEvent`s, and
`StuckItemReconciler`/`ProgressPipelineRunReconciler` sweep abandoned work
(`vidingest.reconciler.*`).

**Never fail a run item without checking it is actually dead.** `phase_updated_at` moves only
on a phase *transition*, so a phase that legitimately runs for hours is indistinguishable from
abandoned work by timestamp alone, and failing a live item invites an operator retry that runs
a second worker over the same video. Two independent answers guard it, because they fail in
opposite directions: `PipelineService.isItemOwned` is blind to other instances but never
wrong about this one, and the `lease_owner`/`lease_expires_at` columns on
`vidingest_pipeline_run_items` see every instance but go stale if this process stops
heartbeating. An item is reaped only when neither claims it. `RunItemLeaseService` owns the
writes, `PipelineService.renewLeases` heartbeats them on a schedule that must stay well under
`vidingest.lease.ttl`, and `ProgressPipelineRunReconciler` leaves a run alone entirely while
any of its items holds a live lease. Lease renewal is scoped by owner, so a heartbeat can
never extend a lease another instance took over.

Ownership is claimed *before* the executor submit and the lease *after* the gate, and the gap
between them is the point: an item queued behind the gate is `PENDING` with no lease, so the
sweep covers `PENDING` too and `isItemOwned` is the only thing that keeps queued work from being
reaped. Nothing else ever revisits a `PENDING` item — before Aug 2026 a process that died with
items queued left them unreachable, their run stuck `IN_PROGRESS`, and every retry refused.
**Retry eligibility asks the same question**: any item not `COMPLETED`, not `CANCELLED` and not
claimed. The run-level "only a FAILED run may be retried" gate is separate and answered *first* —
`RunLifecycleService.prepareRetry` validates and mutates in one call, so it cannot also be what
defers the decision, and a retry that accepts nothing must leave the run `FAILED`.

**A run's `skipPhases` is persisted, and on retry absent is not empty.** The set lives on
`vidingest_pipeline_runs.skip_phases` (`PhaseSetConverter`, one comma-separated column) because
nothing else records what a run was configured to do — it used to exist only in the in-memory
`PipelinePhaseContext`, and both retry endpoints take their set from the request body. So a client
that sent `{"skipPhases": []}` re-enabled every enrichment phase the run had deliberately skipped:
the runs board did exactly that, and a run created without OCR came back calling paddleocr.
`PipelineController.requestedSkips` returns `null` for an absent list and `PipelineService`
resolves that to the run's own set; an empty list stays an explicit "run everything". `prepareRetry`
writes the effective set back, so the next retry inherits the last attempt and the console's phase
picker keeps describing the run in front of it. Reconstructing this client-side is impossible —
a phase after the one that failed was never reached, which no lane can distinguish from skipped.

The executor is virtual-thread-per-task and stays unbounded so shutdown waits only for
in-flight work; concurrency is capped by a `Semaphore` inside `PipelineService`
(`vidingest.ingestion.concurrency`, default 4).

**Run status is derived under a row lock.** `refreshRunState` takes the run with
`PipelineRunRepository.findWithLockById` (`SELECT ... FOR UPDATE`) *before* reading its
items — that ordering is the fix for two items finishing at once stranding the run at
`IN_PROGRESS` forever. Do **not** extend the lock to `ensureRunInProgress`/`updateRunPhase`:
they fire on every phase transition, and every audit-event insert already holds
`FOR KEY SHARE` on that row through its FK, so `FOR UPDATE` there would serialise the hot
path against its own audit trail. `@DynamicUpdate` on `PipelineRun` covers their
whole-row clobber instead.

Every phase service is idempotent (wipe-then-repopulate for the video), which is what
makes per-phase rerun possible: `POST /api/v1/videos/{id}/phases/{phase}/run`
(`VideoPhaseRunnerService`) synchronously re-executes one phase without a new run row.
Only TRANSCRIBE..CONTEXT are reachable that way — METADATA/DOWNLOAD/PERSIST consume a URL,
not a video row, so those need a full pipeline run.

Every phase wipes and repopulates in **one transaction**, taken after its slow work. OCR and
KNOWLEDGE are the two whose loop sits between the read and the write; their transaction covers
only the two statements, so the loop still runs connection-free. Committing the wipe *before* the
loop was the older answer to that constraint and it made every mid-loop failure a silent data
loss. Their failure policies differ on purpose: OCR skips a bad frame and throws only when *no*
frame was readable, while KNOWLEDGE fails on any failed batch — a batch is ~40 segments of
coverage, not one frame, so salvaging the rest would silently narrow the extraction.

**Every ffmpeg invocation goes through `FfmpegRunner`.** Never call `readAllBytes()` on a process
stream before `waitFor`: the stream reaches EOF only when the process exits, so draining on the
waiting thread makes the timeout unreachable for exactly the hung process it exists to kill. The
runner drains on a separate thread, passes `-nostdin`, and closes the child's stdin — ffmpeg reads
stdin by default and blocks forever on a pipe nobody writes.

**Transactions.** `@Transactional` on a `protected`/`private` or self-invoked method does
nothing — `AnnotationTransactionAttributeSource` is `publicMethodsOnly`, and `this.` calls
bypass the proxy regardless. Six services shipped with inert annotations. Phase services do
process and HTTP work in the same method as their writes, so the public driver can never
carry `@Transactional`: inject `TransactionOperations` and wrap only the DB block. Bulk
`deleteBy*` repo methods carry their own `@Modifying @Transactional @Query`. Never open a
transaction around a sidecar, ollama or yt-dlp call — the pool is 10 connections.
`SubprocessTransactionBoundaryIntegrationTest` and `ContextChunkRegenerateIntegrationTest` assert
this from inside the stubbed call, so re-adding `@Transactional` fails a test rather than
production. **Irreversible work goes after the commit, not inside it**: a recursive directory
delete cannot roll back, so `VideoDeleteService` deletes the row first and the artifacts second.

### Data and external services

Schema is Liquibase-only (`ddl-auto=none`): SQL changesets under
`vidingest-server/src/main/resources/db/changelog/changesets/`, registered in
`db.changelog-master.yaml`. New migrations are a new numbered file plus an include —
never edit an applied changeset.

Feature packages under `core/` follow `client → service → domain/repo → mapper → dto`
(MapStruct mappers, Lombok everywhere). External dependencies, all HTTP or process calls:
yt-dlp + ffmpeg as local processes (`core/download`, `core/frames`), whisper (:9000),
diarize-asr (:9001), paddleocr-server (:8002), ollama (:11434) for both embeddings and
knowledge-extraction chat. Embedding providers are swappable behind `EmbeddingsClient`
with `Disabled*` implementations — integration tests turn embeddings off through
`vidingest.search.embeddings.provider=disabled`.

### Tests

`BaseVidingestIntegrationTest` boots the app on a random port against a shared static
`PostgreSQLContainer`, wipes tables in `@BeforeEach` (the rest cascade from videos/runs),
and disables YouTube sync + semantic search. Extend it for anything needing a real schema.
`*WebMvcTest` classes are slice tests under `features/*/web/`; plain `*Test` classes are
unit tests next to the code they cover. `@Tag` is applied inconsistently — don't rely on
`-Dtest.groups` for selection, use `-Dtest=`.

There is no `src/test/resources` anywhere; property overrides go through
`@SpringBootTest(properties=...)` or `@DynamicPropertySource`. Spring Boot 4, so it is
`@MockitoBean`, never `@MockBean`. `PipelinePhase` is sealed and cannot be stubbed — tests
use the real phase impls with mocked services. ffmpeg is faked by overriding
`FrameSamplingService.runFfmpeg`; sidecar HTTP clients by a JDK
`com.sun.net.httpserver.HttpServer` on port 0. Services that take `TransactionOperations`
get `TransactionOperations.withoutTransaction()` in unit tests — no mocking needed.

## Frontend

The operator console lives at [applications/webapp/](applications/webapp/) — **Angular 22, zoneless** (no zone.js),
standalone components, signals, `@if`/`@for`, `provideHttpClient(withFetch())`, typed reactive
forms, lazy routes, SCSS. No component library. See
[docs/vidingest/VidIngest - Web UI.md](docs/vidingest/VidIngest%20-%20Web%20UI.md) for the screens,
the design tokens and the measured API findings behind them.

```bash
cd applications/webapp && npm start
```

`ng serve` runs on :4200 and proxies `/vidingest` to the server on :8051
([proxy.conf.json](applications/webapp/proxy.conf.json)), so the app is same-origin in dev and in production
(the build is served from `classpath:/static` under the `/vidingest` context path). Every request
is relative — there is no environment file and no CORS config.

**The API client is generated, never hand-written.**

```bash
cd applications/webapp && npm run api:gen
```

That curls the live spec to `openapi/vidingest.json` and regenerates
`src/app/api/generated/` (openapi-generator 7, `typescript-angular`). Commit the snapshot and the
generated tree together; never edit the generated tree.

**`--type-mappings=set=Array` is load-bearing.** A Java `Set` field (`skipPhases`) becomes
`uniqueItems: true`, which generates `Set<string>` — and `JSON.stringify(new Set(['OCR']))` is
`"{}"`, so the field silently reaches the server as an object and the request 400s. Keep the flag
on any regeneration.

Every controller method carries an explicit `@Operation(operationId = …)`. Without them springdoc
derives ids from method names, they collide across controllers, and the client gets `list1()`,
`get2()`, `_delete()`. Add one when you add an endpoint.

What the generator cannot give us, and therefore lives by hand in `src/app/core/`:

- `domain.ts` — the seven server enums (`RunStatus`, `PipelineRunPhase`, `PipelineErrorCode`,
  `PipelineRunItemEventType`, `VideoStatus`, `YoutubeChannelStatus`, `TranscriptionStatus`).
  springdoc emits them as bare `string`. **Update this file when a server enum gains a constant.**
- `problem.ts` — the RFC 9457 `ProblemDetail` envelope. No operation in the spec documents a
  4xx/5xx, so error bodies generate as `any`. `errorCode` is a *pipeline* field, never an HTTP one;
  the two are rendered by different components and never merged.
- `time.ts` — server timestamps are naive `LocalDateTime`; the container runs UTC and the browser
  may not, so they are parsed as UTC. Without that, ages read an hour off.

API facts worth not rediscovering: a **FAILED run still reports `phase: "DONE"`** (use
`item.failedPhase` — but see below); **`?live=true` on `/pipelines` is only honoured together with
`ids`** — alone it silently returns every run, so the board queries `status=IN_PROGRESS` and
`status=PENDING`; **`POST /pipelines` answers 400 with a `CreatePipelineRunResponse` body** (not a
ProblemDetail) when every URL was rejected; **`/health/readiness` answers 503 carrying the full
`ReadinessResult`**, so the failing response *is* the report (`app.ts` reads the checks out of
`HttpErrorResponse.error` and says "server unreachable" only on status 0 — treating any error as
unreachable hid the one line naming the broken dependency); and **a 202 on either retry endpoint
does not mean the work was queued** — the same body carries `REJECTED` items with a reason
("already running", "was cancelled"), so the response is read, never discarded.

**`failedPhase` is not always a phase.** It is `CREATED` for an item reaped while still queued and
`DONE` on a clean finish, so `LANE_PHASES.indexOf` answers `-1` — call `isLanePhase()` before
treating it as a position. And **audit `size` is clamped to 500** server-side
(`PipelineAuditQueryService.MAX_PAGE_SIZE`) on an **ascending** feed, so page 0 is the oldest
window and the tail is what the screen needs. The last *page* is not the last *window*: it holds
`total mod 500` events, so on a 501-event run it holds one, and on a 100-URL run (~2200 events)
taking page 4 alone left ninety items with no events at all — and `buildLane` draws an item with no
`ITEM_PHASE_ENTERED` as ten hatched "skipped" boxes, so phases that ran reported themselves as
turned off. `core/audit.ts` takes whole pages from the end (capped at four) and concatenates.

**A page number outlives the list it came from.** Delete the only row on page 2 and the response is
0 rows with a total of 25, so the screen renders its "nothing matches" empty state over 25 rows
that do — and the pager, which lives inside the non-empty branch, is gone with them. Every paged
screen calls `clampPage` ([core/paging.ts](applications/webapp/src/app/core/paging.ts)) for that,
and it consults a resource only while `hasValue()` — a hiccup must not move the operator's page.

Two Angular signal traps this app has already paid for: **`resource.value()` throws
`ResourceValueError` in the error state**, so `@if (r.value())` must never sit before the
`@else if (r.error())` branch that would render the failure — check `error()` first and guard
every other read with `hasValue()`. And **`linkedSignal(() => …)` resets whenever anything it
reads changes identity**, so seeding a selection from a polled array throws the user's pick away
on every tick; pass an explicit stable `source`.

The container runs `-Duser.timezone=UTC` (see the Dockerfile), which is what makes the
parse-as-UTC rule in `core/time.ts` correct rather than a guess.

Production serving is one jar: the Dockerfile builds the console in a node stage with
`--base-href=/vidingest/` and copies it into `resources/static/`, and
`config/SpaStaticResourceConfig` forwards client routes to `index.html` while leaving `api/`,
`actuator`, `v3/` and `swagger-ui` to 404 as JSON.

Design tokens are [applications/webapp/src/styles/_tokens.scss](applications/webapp/src/styles/_tokens.scss). They
override [design-system/vidingest-console/MASTER.md](design-system/vidingest-console/MASTER.md),
which is regenerable search-CLI output and never hand-edited (it is a light-mode landing-page
template with several unusable values). The five design skills in [.claude/skills/](.claude/skills/)
run in a fixed order — see [docs/frontend-skills.md](docs/frontend-skills.md). Never run
`ui-ux-pro-max` and `frontend-design` in the same turn. **`shadcn` is disabled in
`.claude/settings.local.json`: it is React-only and cannot help here.**

## Conventions

Docs in [docs/](docs/) are treated as source-of-truth companions to the code: each page
carries repo-relative code pointers and a "Last reviewed" line. When you move or rename
code that a doc points at, fix the pointer in the same change.

Every merged PR gets a summary in [.claude/pr/](.claude/pr/) — see
[.claude/pr/README.md](.claude/pr/README.md) for the format. Record the decisions and the
things deliberately *not* done, not a rehash of the diff; the point is that the next
session does not re-litigate a settled trade-off. Add the file and its index row in the
same PR.
