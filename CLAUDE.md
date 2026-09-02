# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

**`./vk` is the interface to this repo** — one extensionless registry-driven script at the root.
Bare `./vk` prints the complete surface to stdout and exits 0; `./vk <cmd> --help` is intercepted
by the dispatcher, so it never has a side effect even on `down`, `clean` and `restart`. Exit codes
are 0 ok / 1 runtime / 2 usage / 3 missing dependency / 4 present-but-unusable. Prefer it over raw
`mvnw` / `docker compose` / `npm`; the raw forms below are the cases it deliberately does not cover.

```bash
./vk
```

```bash
./vk setup && ./vk start --build
```

```bash
./vk test
```

```bash
./vk doctor
```

**Targets are arguments to verbs, never verbs**: `./vk start vidingest`, never `./vk vidingest`
(which answers with that exact correction). `./vk list` prints the seven services and
`./vk list groups` the four groups — `all infra sidecars backend`. There are **no target
aliases**: `mcp` is `vidingest-mcp`, `db` is `postgres`. Infra is postgres and nothing else, since
the model runtimes run on the host (see below).

**Three teardown verbs, three blast radii, never aliases of each other.** `stop` halts, `down`
removes this project's containers and network (`--volumes` also its data, prompting, with `--yes`
as the non-interactive escape hatch), and `clean` deletes build artifacts and provably cannot
reach a container. Adding a fifth teardown synonym is how that boundary rots.

**Adding a command means one registry row plus one `cmd_<name>` function** — help, per-command
help and the dispatcher are all generated from that row, and `./vk doctor` runs the drift lint
that fails if a row and a function disagree. The file's own header block says this; read it before
editing. It follows the `project-cli` skill in `~/.claude/skills/`.

`./vk test` is **hermetic** — 412 server unit tests plus 148 console tests, no daemon, no network —
and names on stderr the suite it skipped. `./vk test integration` is the Testcontainers half, and
`./vk test server -- -Dtest=FusePhaseTest` passes anything after `--` to the harness. The raw
Maven forms below still matter for a targeted run, and their traps are unchanged.

**`./vk test cli` is the script's own 116 checks**
([scripts/vk-selftest.sh](scripts/vk-selftest.sh)) and it **mutates**: it cycles the postgres
container and runs the real `clean`, which is why it is a named suite and never in the default
set. Destructive paths are aimed at `VK_PROJECT=vk-selftest`, an isolated compose project sharing
no container or volume, and the harness asserts all five real containers are still up afterwards.
Run it after touching `./vk` — it found two live defects on its first run, both of them a promise
the help text made and the parser broke.

`scripts/compose.sh` is the raw `docker compose` escape hatch with the same file layering.

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

Optional footprints: `./vk start sidecars` (paddleocr-server, diarize-asr) and
`./vk start vidingest-mcp`. `./vk logs -f vidingest` follows the server; `./vk console` opens the
Spring Shell CLI and `./vk shell --in vidingest` an OS shell beside it. Host ports live in
[compose/ports.env](compose/ports.env); everything binds `127.0.0.1` (`VK_BIND_ADDR`).

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
transaction around a sidecar, LLM or yt-dlp call — the pool is 10 connections.
`SubprocessTransactionBoundaryIntegrationTest` and `ContextChunkRegenerateIntegrationTest` assert
this from inside the stubbed call, so re-adding `@Transactional` fails a test rather than
production. **Irreversible work goes after the commit, not inside it**: a recursive directory
delete cannot roll back, so `VideoDeleteService` deletes the row first and the artifacts second.

### Data and external services

Schema is Liquibase-only (`ddl-auto=none`): SQL changesets under
`vidingest-server/src/main/resources/db/changelog/changesets/`, registered in
`db.changelog-master.yaml`. New migrations are a new numbered file plus an include —
never edit an applied changeset.

**The first six changesets are grouped by scope, not by history** (`001-pipeline`, `002-transcription`,
`003-frames-ocr`, `004-knowledge`, `005-search`, `006-youtube-channels`), so a table's current shape
reads in one place instead of across a migration and three later `ALTER`s. That consolidation
rewrote every changeset id and checksum, which was only possible because the database was recreated
from a backup in the same change (Aug 2026) — it is **not** repeatable against a populated
changelog. From here on, a schema change is a new numbered file, as it always was —
`007-run-created-at-index.sql` is the first one added since.

**Two index rules the schema has already broken once.** A single-column index whose column is the
leftmost prefix of an existing composite or unique index is dead weight — the planner just prefers
the narrower one, which makes `pg_stat_user_indexes` read as if both were needed. Seven of those
were dropped, and the consolidated changesets simply never create them — each omission carries its
reason inline. And there is no GIN index on any `metadata` column: nothing queries JSONB
by content (no `@>`, no `->>`), so all three were pure write cost, the `videos` one at 1656 kB
against three rows. Add one back only alongside the query that needs it.

**`multimodal_segments` stores speaker *labels*, not ids.** DIARIZE is wipe-then-repopulate, so
re-running it alone recreates every speaker row under a new uuid; the old `speaker_ids UUID[]` had
no foreign key to catch that and served ids for deleted rows. `UNIQUE (video_id, label)` already
makes the label the natural key and a re-run reproduces it, so the reference cannot dangle.

The database is **`pgvector/pgvector:pg17`**, the same tag `BaseVidingestIntegrationTest`
gives Testcontainers — keep those two equal, or a pg/pgvector behaviour difference can pass
tests and fail in compose. `vector` is the only extension the schema creates and there are
no hypertables, no `time_bucket` and no full-text search anywhere, so the
`timescale/timescaledb-ha:pg17` image this replaced (Aug 2026) was preloading `timescaledb`
and `pg_textsearch` for nothing. Don't go back to it for "time-series" reasons: pipeline runs
are queried by id and status, not by time range. Stay on pg17 unless you also move the volume
mount — postgres 18 relocated PGDATA to `/var/lib/postgresql/<major>/docker`, which makes the
volume major-version-specific.

Feature packages under `core/` follow `client → service → domain/repo → mapper → dto`
(MapStruct mappers, Lombok everywhere). External dependencies, all HTTP or process calls:
yt-dlp + ffmpeg as local processes (`core/download`, `core/frames`), the two optional sidecars
diarize-asr (:9001) and paddleocr-server (:8002), and **a model runtime on the host** for
embeddings, knowledge chat and transcription.

**There is no `llm` container and no `whisper` container** (removed Aug 2026). Both ran on CPU
inside the Docker VM, which is the one thing an Apple-Silicon host is good at and a Linux VM on it
is not; compose now defaults all three model connections to `${VK_HOST_LLM_URL}`
(`http://host.docker.internal:8000/v1`, an oMLX server). The host process must bind `0.0.0.0` — a
container arrives on the bridge address, so a loopback-only listener refuses it. Don't reintroduce
the containers to "make the stack self-contained": the reason they went is measured, not tidiness.

**No LLM caller is single-protocol.** Embeddings, knowledge chat and transcription each have two
implementations — `ollama`/`openai-compatible` for the first two, `whisper-asr`/`openai-compatible`
for the third — and **a router picks per call**, not `@ConditionalOnProperty` at startup
(`EmbeddingsClientRouter`, `QueryEmbeddingProviderRouter`, `KnowledgeChatClientRouter`,
`TranscriptionClientRouter`). Per call because the provider is runtime-editable (below); a bean
chosen at startup cannot follow a property that changes afterwards. The cost is that a bad provider
value fails the *phase* rather than the context — the connections API validates it on the way in
instead. The provider-named classes and packages (`OllamaEmbeddingsClient`,
`core/knowledge/client/ollama/`, `core/transcription/client/whisper/`) are honest — they speak that
wire protocol — while every neutral surface is named for the role (`/api/v1/health/llm`,
`LlmStatus`, `VIDINGEST_LLM_BASE_URL`, `vidingest.transcription.*`). Embeddings additionally has a
`Disabled*` floor; integration tests use `vidingest.search.embeddings.provider=disabled`.

**`openai` is a fourth provider value and not a synonym for `openai-compatible`.** All three
connections take it; all three route it to the same client. Only the knowledge-chat *body* differs,
and only because api.openai.com answers **400** where every local runtime silently ignores: it
wants `max_completion_tokens` not `max_tokens`, rejects any `temperature` but the default on the
reasoning models, and validates a `strict: true` schema against rules the shared one breaks
(`required` must list every key of `properties`, every object needs `additionalProperties: false`).
`OpenAiCompatibleKnowledgeChatClient` picks the dialect from the configured provider per call —
a second bean would need a router change and a `@Qualifier` for a fifteen-line delta. Don't merge
the two values: `max_completion_tokens` is unknown to oMLX and llama.cpp, so one body cannot serve
both. Two knowingly-taken ceilings live there as `ponytail:` comments — no `temperature` control at
all under `openai`, and `strict: false` instead of making the schema OpenAI-strict for everyone.
**Anything that probes a keyed endpoint must send the key**: `ConnectionProbeService` and
`LlmStatusService` both did not, so `POST .../test` reported 401 and the console's LLM rail read
"unreachable" while the phase itself worked — a health check reporting its own missing header.

**Embedding width is a column type, not a preference.** `VECTOR(1536)` is what the schema declares
and `expectedDimensions` is checked on every response. No oMLX-supported embedding architecture is
natively 1536, so `vidingest.search.embeddings.dimensions` sends the OpenAI `dimensions` field
(Matryoshka truncation) and `Qwen3-Embedding-4B` is cut from 2560 down to it. Sent only when set —
some OpenAI-compatible servers reject an unknown field — and it only ever truncates, so a model
narrower than the column cannot be padded up to it. Two oMLX traps behind that: it decides a
causal-LM embedder by looking for `embed` in the **directory name** (so `gte-Qwen2-1.5B-instruct-...`
is filed as an LLM despite being 1536-wide), and its embedding engine has no `qwen2` support at all,
so overriding the type does not rescue it.

**Six connections are editable at runtime**: `GET/PUT/DELETE /api/v1/connections/{name}` and
`POST .../test`, over `EMBEDDINGS`, `KNOWLEDGE`, `TRANSCRIPTION`, `DIARIZATION`, `FRAME_SAMPLE`, `OCR`
(`connections/`, table `vidingest_connections`, console screen `features/settings/`). A row is an
**override**, not the configuration: absent, the environment value applies.

`FRAME_SAMPLE` is the odd one and is deliberately here: it is local ffmpeg, so it carries a phase
toggle and nothing else — `supportsBaseUrl` is false, `base_url` is nullable since `009`, and the
probe answers "no endpoint" rather than "unreachable". It is on this API because OCR's toggle is
meaningless without it: `OcrPhase.applies` now requires `vidingest.frames.enabled` as well as its
own, so a screen that could flip OCR but not frames was one click from a run that entered OCR and
read an empty frame set. `supportsBaseUrl`/`supportsModel`/`supportsEnabled` are served per row so
the console renders only the controls the server would honour — mirror none of it client-side. `ConnectionSettingsService`
snapshots the environment values before applying rows, which is the only reason `DELETE` can mean
"back to what `.env` said". It applies on `ApplicationReadyEvent`, **not** `@PostConstruct` — the
table does not exist yet at bean-init time.

The mechanism is that `@ConfigurationProperties` beans are mutable singletons and **every client
reads its base URL per call**. So the four transports (`transcriptionRestClient`,
`diarizationRestClient`, `ocrRestClient`, `knowledgeChatRestClient`) deliberately have **no
`.baseUrl(...)`** — they contribute only the request factory. Adding one back silently pins the URL
to startup and the settings API goes quiet. Timeouts *are* still startup-bound for that reason, and
are deliberately absent from the API rather than shown as a value the client is not using.
API keys are stored plaintext and **never returned** — `ConnectionSummary` carries `hasApiKey`, and
an absent `apiKey` on update keeps the stored one while `""` clears it.

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

- `domain.ts` — server enums springdoc emits as bare `string`. **Six are mirrored as exported
  lists**: `RunStatus`, `PipelineRunPhase`, `PipelineErrorCode`, `PipelineRunItemEventType`,
  `VideoStatus`, and `KnowledgeUnitType` — that last one lives in **`vidingest-api`**, not the
  server, which is exactly how it stayed off this list while the console mirrored it as
  `KNOWLEDGE_TYPES`. Two more, `YoutubeChannelStatus` and `TranscriptionStatus`, have no list at
  all: their values reach the UI only as cases in `statusVar()`, so a new constant there needs a
  new `case`, not a new array. **Update this file when any of the eight gains a constant** — nothing
  fails if you don't, the console just renders the value as unknown.
  `ConnectionName` is deliberately **not** here: springdoc emits it as a real enum schema, so the
  generated client already types it as a literal union. Nor is the provider list — the connections
  API serves `supportedProviders`, `supportsModel` and `supportsEnabled` per row precisely so the
  settings screen cannot drift from what the server accepts. Mirror only what the spec loses.
- `problem.ts` — the RFC 9457 `ProblemDetail` envelope. No operation in the spec documents a
  4xx/5xx, so error bodies generate as `any`. `errorCode` is a *pipeline* field, never an HTTP one;
  the two are rendered by different components and never merged.
- `time.ts` — server timestamps carry an explicit offset (`OffsetDateTime`, written at UTC), so
  `new Date` is enough. It deliberately does **not** fall back to appending `Z` for a zoneless
  value: that fallback is what hid an hour of skew for a release, and a zoneless timestamp is now
  a server bug that should read as one. Test fixtures must carry the offset too — comparing a
  zoneless literal against a `Z` one silently shifts by the runner's zone.

API facts worth not rediscovering: a **FAILED run still reports `phase: "DONE"`** (use
`item.failedPhase` — but see below); **`?live=true` on `/pipelines` is only honoured together with
`ids`** — alone it silently returns every run, so the board queries `status=IN_PROGRESS` and
`status=PENDING`; **`POST /pipelines` answers 400 with a `CreatePipelineRunResponse` body** (not a
ProblemDetail) when every URL was rejected; **`/health/ready` answers 503 carrying the full
`ReadinessResult`**, so the failing response *is* the report (`app.ts` reads the checks out of
`HttpErrorResponse.error` and says "server unreachable" only on status 0 — treating any error as
unreachable hid the one line naming the broken dependency); and **a 202 on either retry endpoint
does not mean the work was queued** — the same body carries `REJECTED` items with a reason
("already running", "was cancelled"), so the response is read, never discarded.

**`failedPhase` is not always a phase.** It is `CREATED` for an item reaped while still queued and
`DONE` on a clean finish, so `LANE_PHASES.indexOf` answers `-1` — call `isLanePhase()` before
treating it as a position. **Run-level `phase` carries the same two markers**: a run is `CREATED`
from `RunLifecycleService.createPipelineRun` until METADATA starts and `prepareRetry` writes it back, so it is
what a run reports right after a retry — the runs board renders that as `queued`, never as a step. And **audit `size` is clamped to 500** server-side
(`PipelineAuditQueryService.MAX_PAGE_SIZE`) on an **ascending** feed, so page 0 is the oldest
window and the tail is what the screen needs. The last *page* is not the last *window*: it holds
`total mod 500` events, so on a 501-event run it holds one, and on a 100-URL run (~2200 events)
taking page 4 alone left ninety items with no events at all — and `buildLane` draws an item with no
`ITEM_PHASE_ENTERED` as ten hatched "skipped" boxes, so phases that ran reported themselves as
turned off. `core/audit.ts` takes whole pages from the end (capped at four) and concatenates.

**Two screens draw lanes**, run detail and ingest, and both take the run, its audit tail and the
built lanes from `core/watch-run.ts` — declared inline they cost the same correction twice (the tail
paging above, and the lane build moving out of the template's read path). **Which** failure a screen
shows is `firstFailure` in `core/problem.ts`: load failures in precedence order, with the action the
operator just took in front of the call as `actionFailure() ?? firstFailure(…)`. Both are called from an injection context, like
`syncQueryParams` and `clampPage`.

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

The container still runs `-Duser.timezone=UTC` (see the Dockerfile), but nothing depends on it
any more: entities are `OffsetDateTime` and every `now()` is `OffsetDateTime.now(ZoneOffset.UTC)`,
so the stored instant and the wire format are the same whatever zone the JVM is in. That is
asserted from a JVM pinned to `America/Los_Angeles` in `MetadataExtractorTest`.

**The console is its own image.** [applications/webapp/Dockerfile](applications/webapp/Dockerfile)
builds the bundle with `--base-href=/vidingest/` and serves it from nginx on `WEBAPP_PORT` (8052),
proxying the API to `vidingest:8051`. The server used to bake the bundle into `classpath:/static`
and serve it through a `SpaStaticResourceConfig`; both are gone, so the vidingest image is the API
alone and there is exactly one console.

nginx proxies rather than the app knowing where the server is, because **every console request is
relative** (`API_BASE = '/vidingest'`) and the server has no CORS config — the two must stay
same-origin. The proxied prefixes in [nginx.conf](applications/webapp/nginx.conf) (`api/`,
`actuator`, `v3/`, `swagger-ui`) are now the **only** record of the server's surface: a path that
should reach the server but matches none of them falls into the SPA branch and returns
`index.html` with a **200**, which looks like a blank screen, not a 404. Two traps already paid for
and commented where they live: `return 302` needs `absolute_redirect off` or it sends the browser
to the container's internal port, and the healthcheck must use `127.0.0.1` because `localhost`
resolves to `::1` first while nginx listens on IPv4 only.

Design tokens are [applications/webapp/src/styles/_tokens.scss](applications/webapp/src/styles/_tokens.scss).
**Two themes live there**, dark and a separately measured light ramp — not an inversion, since the
green that reads 11.6:1 on near-black is 1.6:1 on near-white. The OS preference applies with no
JavaScript (`@media (prefers-color-scheme: light)` guarded by `:root:not([data-theme='dark'])`); a
stored choice lands on `<html data-theme>` from an inline script in `index.html` **before first
paint**. So **never write a raw hex in a component** — it cannot follow the theme. Washes go over
the token: `color-mix(in srgb, var(--st-failed-fill) 18%, transparent)`. The tokens
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

[docs/map/](docs/map/) is the change-impact map: one card per noun with `path:line` citations,
five process cards, and [effects/](docs/map/effects/CONTEXT.md) — "I am changing X, open these".
Read it *before* an edit; it answers what a change hits, not why the design is what it is.

Every merged PR gets a summary in [.claude/pr/](.claude/pr/) — see
[.claude/pr/README.md](.claude/pr/README.md) for the format. Record the decisions and the
things deliberately *not* done, not a rehash of the diff; the point is that the next
session does not re-litigate a settled trade-off. Add the file and its index row in the
same PR.
