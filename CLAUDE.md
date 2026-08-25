# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

Build (Java 25 + Maven 3.9+ enforced by the root POM; `./mvnw` pins the build):

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
opposite directions: `PipelineService.isItemInFlight` is blind to other instances but never
wrong about this one, and the `lease_owner`/`lease_expires_at` columns on
`vidingest_pipeline_run_items` see every instance but go stale if this process stops
heartbeating. An item is reaped only when neither claims it. `RunItemLeaseService` owns the
writes, `RunItemLeaseHeartbeat` renews them on a schedule that must stay well under
`vidingest.lease.ttl`, and `ProgressPipelineRunReconciler` leaves a run alone entirely while
any of its items holds a live lease. Lease renewal is scoped by owner, so a heartbeat can
never extend a lease another instance took over.

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

TRANSCRIBE, DIARIZE, FRAME_SAMPLE, FUSE and CONTEXT wipe and repopulate in **one
transaction**. OCR and KNOWLEDGE deliberately do not: they wipe before a minutes-long
sidecar/LLM loop and persist after, because one transaction across that loop would pin a
pooled connection for its whole duration. They throw when nothing was produced rather than
reporting a successful zero over freshly-wiped rows.

**Transactions.** `@Transactional` on a `protected`/`private` or self-invoked method does
nothing — `AnnotationTransactionAttributeSource` is `publicMethodsOnly`, and `this.` calls
bypass the proxy regardless. Six services shipped with inert annotations. Phase services do
process and HTTP work in the same method as their writes, so the public driver can never
carry `@Transactional`: inject `TransactionOperations` and wrap only the DB block. Bulk
`deleteBy*` repo methods carry their own `@Modifying @Transactional @Query`. Never open a
transaction around a sidecar, ollama or yt-dlp call — the pool is 10 connections.

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

## Conventions

Docs in [docs/](docs/) are treated as source-of-truth companions to the code: each page
carries repo-relative code pointers and a "Last reviewed" line. When you move or rename
code that a doc points at, fix the pointer in the same change.

Every merged PR gets a summary in [.claude/pr/](.claude/pr/) — see
[.claude/pr/README.md](.claude/pr/README.md) for the format. Record the decisions and the
things deliberately *not* done, not a rehash of the diff; the point is that the next
session does not re-litigate a settled trade-off. Add the file and its index row in the
same PR.
