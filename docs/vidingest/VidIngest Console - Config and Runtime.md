# VidIngest Console - Config and Runtime

- **Primary packages**: `com.tradinglabs.vidingest.config`
- **Last reviewed**: 2026-08-26
- **Status**: stable

## Quickstart (for agents)

Configuration is managed through Spring Boot property files and `@ConfigurationProperties` classes.

**Implementation pointers**

| File | Role |
|------|------|
| `applications/vidingest/vidingest-server/src/main/resources/application.properties` | Server base config (REST + DB) |
| `applications/vidingest/vidingest-server/src/main/resources/application-dev.properties` | Server dev profile: verbose SQL/transaction logging |
| `applications/vidingest/vidingest-server/src/main/resources/application-docker.properties` | Server docker profile: container-specific overrides |
| `applications/vidingest/vidingest-mcp/src/main/resources/application.properties` | MCP app base config (MCP SSE + downstream server URL) |
| `applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/config/VideoDownloadConfig.java` | `vidingest.download.*` properties |
| `applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/config/VideoStorageConfig.java` | `vidingest.storage.*` properties |
| `applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/config/VideoSearchConfig.java` | `vidingest.search.*` properties |
| `applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/config/ProjectPathResolver.java` | Auto-detects project root and default storage paths |
| `applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/config/JacksonConfig.java` | Explicit `ObjectMapper` bean used by download flows |
| `applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/config/LiquibaseConfig.java` | Explicit Liquibase bean wiring |
| `applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/config/TransactionConfig.java` | `@EnableTransactionManagement` |
| `applications/vidingest/vidingest-cli/src/main/resources/application.properties` | CLI config (server base URL + timeouts) |

## Profiles

| Profile | Activation | Purpose |
|---------|-----------|---------|
| (default) | No profile set | Local development with sensible defaults |
| `dev` | `--spring.profiles.active=dev` | Verbose SQL logging, transaction tracing |
| `docker` | `SPRING_PROFILES_ACTIVE=docker` | Container paths, Docker networking |

## Core properties

### Application

| Property | Default | Description |
|----------|---------|-------------|
| `spring.application.name` | `vidingest-server` | Application name |
| `spring.main.web-application-type` | `servlet` | Server uses Spring MVC; CLI uses `none` |
| `server.port` | `8051` | HTTP port for server REST API |
| `server.servlet.context-path` | `/vidingest` | Base context path |
| `spring.threads.virtual.enabled` | `true` | Use virtual threads (Java 25) |

The server has no `spring-shell` dependency; the interactive shell lives only in
`vidingest-cli`, which sets `spring.main.web-application-type=none` plus
`spring.shell.interactive.enabled=true`.

### Ingestion concurrency and reconcilers

| Property | Default | Description |
|----------|---------|-------------|
| `vidingest.ingestion.concurrency` | `4` | How many run items execute phases at once |
| `vidingest.youtube.sync.concurrency` | `4` | Channels synced at once within a tick (each runs yt-dlp) |
| `vidingest.reconciler.itemStaleAfter` | `PT1H` | A `PENDING` or `IN_PROGRESS` item untouched for longer is failed — unless some process still claims it |
| `vidingest.reconciler.intervalMs` | `300000` | Stuck-item sweep interval |
| `vidingest.reconciler.initialDelayMs` | `60000` | Delay before the first sweep |

`vidingest.ingestion.concurrency` is a permit gate inside `PipelineService`, not the executor
size — `vidingestIngestionExecutor` stays virtual-thread-per-task so shutdown waits only for
in-flight work instead of draining a queue. Each item drives yt-dlp, ffmpeg and a sidecar, so
keep this at or below `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` (default `10`); the
compose stack passes it through as `VIDINGEST_INGESTION_CONCURRENCY`.

See [Concurrency and run-status aggregation](#concurrency-and-run-status-aggregation) for what
the reconcilers do and do not fix.

### MCP app (`vidingest-mcp`)

The MCP server runs as a separate Spring Boot app (`vidingest-mcp`) and delegates all tool actions to the REST server via `vidingest-client`.

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `8055` | HTTP port for MCP SSE transport |
| `server.servlet.context-path` | `/vidingest` | Base context path (matches REST server) |
| `spring.ai.mcp.server.enabled` | `true` | Enables MCP server transport auto-configuration |
| `spring.ai.mcp.server.name` | `vidingest` | MCP server identifier (tool prefix `vidingest_`) |
| `spring.ai.mcp.server.type` | `SYNC` | Synchronous tool execution model |
| `vidingest.server.base-url` | `http://localhost:8051/vidingest` | Downstream REST server base URL |
| `vidingest.server.connect-timeout` | `5s` | HTTP connect timeout for downstream calls |
| `vidingest.server.read-timeout` | `10m` | HTTP read timeout for downstream calls |

### Database

All database properties support environment variable overrides.

| Property | Default | Env override | Description |
|----------|---------|-------------|-------------|
| `spring.datasource.url` | `jdbc:postgresql://localhost:3030/tradingPlatformDB` | `DB_HOST`, `DB_PORT`, `DB_NAME` | JDBC URL |
| `spring.datasource.username` | `dealer` | `DB_USERNAME` | Database user |
| `spring.datasource.password` | `dev_dealer` | `DB_PASSWORD` | Database password |
| `spring.jpa.hibernate.ddl-auto` | `none` | - | Schema managed by Liquibase |
| `spring.liquibase.enabled` | `true` | - | Run migrations on startup |

### Storage (`vidingest.storage.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `vidingest.storage.video-path` | Auto-resolved by `ProjectPathResolver` | Directory for downloaded videos |

**Path resolution priority**:

1. Explicit property value (if set and non-empty)
2. `ProjectPathResolver` auto-detection: `{projectRoot}/package/vidingest/videos`
3. `VIDEO_KNOWLEDGE_ROOT` env var (if set, used to locate project root)
4. Auto-detect by walking up from `user.dir` looking for root `pom.xml`
5. Fallback: current working directory

### Whisper transcription (`vidingest.whisper.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `vidingest.whisper.base-url` | `http://localhost:9000` | Whisper ASR webservice base URL (`http://whisper:9000` in docker) |

### Download (`vidingest.download.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `vidingest.download.tool` | `yt-dlp` | Download tool executable |
| `vidingest.download.format` | `bestvideo+bestaudio` | yt-dlp format selector |
| `vidingest.download.container` | `mp4` | Merge output format |
| `vidingest.download.retries` | `3` | Download retry count |
| `vidingest.download.timeout-seconds` | `0` | yt-dlp watchdog timeout in seconds (`0` disables timeout) |
| `vidingest.download.audio-codec` | (not set) | Audio codec: `aac`, `mp3`, `opus`, `vorbis`, `m4a` |
| `vidingest.download.audio-bitrate` | (not set) | Audio bitrate in kbps |
| `vidingest.download.subtitles` | `false` | Download subtitles |
| `vidingest.download.subtitle-languages` | (empty) | Comma-separated language codes |
| `vidingest.download.subtitle-format` | `vtt` | Subtitle format |
| `vidingest.download.subtitles-separate` | `true` | Write subs to separate files |
| `vidingest.download.thumbnail` | `false` | Download thumbnail |
| `vidingest.download.thumbnail-format` | `jpg` | Thumbnail format |
| `vidingest.download.embed-metadata` | `true` | Embed metadata in video file |
| `vidingest.download.embed-subtitles` | `false` | Embed subtitles in video |
| `vidingest.download.embed-thumbnail` | `false` | Embed thumbnail as cover art |
| `vidingest.download.embed-chapters` | `false` | Embed chapter markers |
| `vidingest.download.write-info-json` | `false` | Save .info.json file |
| `vidingest.download.write-description` | `false` | Save .description file |
| `vidingest.download.restrict-filenames` | `true` | ASCII-only filenames |
| `vidingest.download.windows-filenames` | `false` | Windows-compatible names |
| `vidingest.download.concurrent-fragments` | `1` | Parallel fragment downloads |

### Semantic search (`vidingest.search.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `vidingest.search.semantic-enabled` | `false` (base), `true` (docker/dev) | Enables semantic pgvector search commands/tools |

When semantic search is enabled, VidIngest also requires a `QueryEmbeddingProvider` implementation to produce the query embedding vector.

#### Docker (recommended) env vars

When running VidIngest via the platform Docker stack (`SPRING_PROFILES_ACTIVE=docker`), semantic search is enabled by default and embeddings are configured to use **Ollama**.

- `VIDINGEST_SEARCH_SEMANTIC_ENABLED=true`
- `VIDINGEST_EMBEDDINGS_PROVIDER=ollama`
- `VIDINGEST_OLLAMA_BASE_URL=http://ollama:11434`
- `VIDINGEST_OLLAMA_EMBED_MODEL=rjmalagon/gte-qwen2-1.5b-instruct-embed-f16`
- `VIDINGEST_EMBEDDINGS_EXPECTED_DIMENSIONS=1536`
- `VIDINGEST_OLLAMA_TIMEOUT=30s` (or `VIDINGEST_EMBEDDINGS_TIMEOUT=30s`)
- `VIDINGEST_OLLAMA_TRUNCATE=true`

Notes:

- `VIDINGEST_OLLAMA_BASE_URL` must be reachable **from inside the container**; use `http://ollama:11434` for the docker stack.
- Semantic search results depend on context-chunk generation during ingestion; ingest without `TRANSCRIBE` or `CONTEXT` in `skipPhases`.
- For videos ingested before semantic search was enabled, regenerate context chunks via `POST /vidingest/api/v1/videos/{videoId}/context/regenerate`.

#### Troubleshooting (semantic search)

- **409 Conflict: semantic search disabled**
  - Cause: `vidingest.search.semantic-enabled=false`.
  - Fix: set `VIDINGEST_SEARCH_SEMANTIC_ENABLED=true`.
- **409 Conflict: no query embedding provider configured**
  - Cause: embeddings provider is misconfigured or inactive.
  - Fix (Ollama): set `VIDINGEST_EMBEDDINGS_PROVIDER=ollama` and configure `VIDINGEST_OLLAMA_BASE_URL` + `VIDINGEST_OLLAMA_EMBED_MODEL`.
  - Fix (OpenAI-compatible): set `VIDINGEST_EMBEDDINGS_PROVIDER=openai-compatible` and configure `VIDINGEST_EMBEDDINGS_BASE_URL` + `VIDINGEST_EMBEDDINGS_MODEL` (+ `VIDINGEST_EMBEDDINGS_API_KEY` if needed).
- **Upstream embeddings failure**
  - Cause: embeddings call failed (network, auth, model mismatch, timeout).
  - Fix: verify provider URLs, model name, and connectivity from inside the container; increase timeout via `VIDINGEST_OLLAMA_TIMEOUT` / `VIDINGEST_EMBEDDINGS_TIMEOUT`.

## Docker configuration

### Compose layout

There is no single `docker-compose.yml`. `compose.yml` holds only the shared network and
the named volumes; services live in split files layered on top of it:

| File | Services |
|------|----------|
| `compose/infra/infra.yml` | `timescaledb`, `ollama`, `whisper`, `paddleocr-server`, `diarize-asr` |
| `compose/services.yml` | `vidingest` (REST server) |
| `compose/cli.yml` | `vidingest-cli` |
| `compose/mcp.yml` | `vidingest-mcp` |

`scripts/tradey.sh` and `scripts/compose.sh` apply the layering; running bare
`docker compose` from the repo root sees only the empty base file. Host ports come from
`compose/ports.env` and everything publishes to `127.0.0.1` unless `VK_BIND_ADDR` says
otherwise.

### Storage in containers

Storage is **named volumes**, not bind mounts — nothing under `package/` is mounted into
a container. `package/` is only used when the server runs on the host, where
`ProjectPathResolver` resolves `{projectRoot}/package/vidingest/videos`.

| Volume | Mounted at | Used by |
|--------|-----------|---------|
| `vidingest_data` | `/data/videos` | `vidingest` (rw) — `application-docker.properties` sets `vidingest.storage.video-path=/data/videos`; also `paddleocr-server` as `:ro` so OCR reads frames in place |
| `app_logs` | `/app/logs` | `vidingest`, `vidingest-mcp` (`LOG_DIR=/app/logs`) |
| `timescaledb_data` | `/home/postgres/pgdata/data` | `timescaledb` |
| `ollama_data` | `/root/.ollama` | `ollama` |
| `ai_models` | `/models` | shared model cache for `ollama`, `whisper` (`ASR_MODEL_PATH=/models/whisper`), `paddleocr-server`, `diarize-asr` |

Transcript sidecars are written next to the downloaded video file, so under
`/data/videos/...` in a container.

### Dockerfile (`vidingest-server`)

- Build stage: Eclipse Temurin 25 JDK Alpine + Maven 3.9.11 (via `docker/scripts/install-maven.sh`)
- Runtime stage: Eclipse Temurin 25 JRE Alpine
- Extra tools: `python3`, `py3-pip`, `ffmpeg`, `deno`, plus `yt-dlp` via pip
- User: `spring:spring` (uid/gid 1000, from `docker/scripts/create-app-user.sh`)
- JVM: `-Xms256m -Xmx768m -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC`
- Container memory: `mem_limit: 768M`, `mem_reservation: 256M`
- Health check: `GET /vidingest/api/v1/health/ready` from inside the container
- Build context is the repository root so the Maven reactor can resolve sibling modules

An OpenTelemetry Java agent (pinned 2.26.1) is downloaded into `/otel` by all three
Dockerfiles but is **not** wired into `JAVA_TOOL_OPTIONS`, so nothing loads it at runtime.

### Running with Docker

```bash
./scripts/tradey.sh start vidingest --build
```

```bash
./scripts/tradey.sh logs -f vidingest
```

```bash
./scripts/tradey.sh shell vidingest
```

Override credentials or any other setting through the environment before starting —
`compose/services.yml` passes `SPRING_DATASOURCE_*` through, and the properties files
accept `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`.

## Logging

### Configuration

Log config is in `logback-spring.xml`, which imports `logback-common.xml` from the `common-logging` dependency.

| Output | Description |
|--------|-------------|
| CONSOLE | Standard output |
| PLAIN_FILE | Plain text log file |
| JSON_FILE | Structured JSON log file |
| ERROR_FILE | Error-only log file |

Log directory: `${user.dir}/../../../package/logs/vidingest` (overridable via `LOG_BASE_DIR`)

### Log levels (default profile)

| Logger | Level |
|--------|-------|
| root | INFO |
| `com.tradinglabs` | DEBUG |
| `org.springframework.shell` | INFO |

### Log levels (dev profile)

Adds verbose SQL/transaction tracing on top of defaults:

| Logger | Level |
|--------|-------|
| `org.hibernate.SQL` | DEBUG |
| `org.hibernate.type.descriptor.sql.BasicBinder` | TRACE |
| `org.springframework.transaction` | DEBUG |
| `liquibase` | DEBUG |

## Knowledge-extraction properties (M2–M8)

All disabled by default (fusion is the exception — pure-Java, defaults on). Full
description and operational notes in
[Knowledge Extraction](VidIngest%20Console%20-%20Knowledge%20Extraction.md).

### Speaker diarization (M2)
```properties
vidingest.diarization.enabled=${VIDINGEST_DIARIZATION_ENABLED:false}
vidingest.diarization.base-url=${VIDINGEST_DIARIZATION_BASE_URL:http://localhost:9001}
vidingest.diarization.max-speakers=${VIDINGEST_DIARIZATION_MAX_SPEAKERS:8}
vidingest.diarization.min-overlap-seconds=${VIDINGEST_DIARIZATION_MIN_OVERLAP_SECONDS:0.25}
```
Requires `HUGGINGFACE_TOKEN` on the `diarize-asr` sidecar — accept the
`pyannote/speaker-diarization-3.1` EULA on HuggingFace first.

### ffmpeg
```properties
vidingest.ffmpeg.timeout=${VIDINGEST_FFMPEG_TIMEOUT:PT20M}
```
Ceiling on the audio-extraction pass shared by TRANSCRIBE and DIARIZE. Frame sampling has its own
(`vidingest.frames.ffmpeg-timeout`) because a full keyframe pass and a stream copy to WAV are not
the same workload. Every invocation goes through `FfmpegRunner`, which drains the process output
on a separate thread — draining on the waiting thread makes the timeout unreachable, since the
output stream only reaches EOF when the process exits.

### Frame sampling (M3)
```properties
vidingest.frames.enabled=${VIDINGEST_FRAMES_ENABLED:false}
vidingest.frames.interval-seconds=${VIDINGEST_FRAMES_INTERVAL_SECONDS:10}
vidingest.frames.scene-change-threshold=${VIDINGEST_FRAMES_SCENE_CHANGE_THRESHOLD:0.35}
vidingest.frames.max-frames-per-video=${VIDINGEST_FRAMES_MAX_PER_VIDEO:600}
vidingest.frames.frames-dir-name=frames
vidingest.frames.jpeg-quality=2
vidingest.frames.ffmpeg-timeout=${VIDINGEST_FRAMES_FFMPEG_TIMEOUT:PT20M}
```
Pure-Java + ffmpeg — no sidecar.

### OCR (M4)
```properties
vidingest.ocr.enabled=${VIDINGEST_OCR_ENABLED:false}
vidingest.ocr.base-url=${VIDINGEST_OCR_BASE_URL:http://localhost:8002}
vidingest.ocr.languages=${VIDINGEST_OCR_LANGUAGES:en}
vidingest.ocr.min-confidence=${VIDINGEST_OCR_MIN_CONFIDENCE:0.5}
vidingest.ocr.max-results-per-video=${VIDINGEST_OCR_MAX_RESULTS_PER_VIDEO:10000}
```

### Multimodal fusion (M5)
```properties
vidingest.fusion.enabled=${VIDINGEST_FUSION_ENABLED:true}
vidingest.fusion.window-seconds=${VIDINGEST_FUSION_WINDOW_SECONDS:30.0}
vidingest.fusion.window-overlap-seconds=${VIDINGEST_FUSION_WINDOW_OVERLAP_SECONDS:5.0}
vidingest.fusion.max-segments-per-video=${VIDINGEST_FUSION_MAX_SEGMENTS_PER_VIDEO:2000}
```

### Knowledge extraction (M6)
```properties
vidingest.knowledge.enabled=${VIDINGEST_KNOWLEDGE_ENABLED:false}
vidingest.knowledge.provider=${VIDINGEST_KNOWLEDGE_PROVIDER:ollama}
vidingest.knowledge.chat-model=${VIDINGEST_KNOWLEDGE_CHAT_MODEL:qwen2.5:14b-instruct}
vidingest.knowledge.base-url=${VIDINGEST_KNOWLEDGE_BASE_URL:${VIDINGEST_OLLAMA_BASE_URL:http://localhost:11434}}
vidingest.knowledge.temperature=${VIDINGEST_KNOWLEDGE_TEMPERATURE:0.2}
vidingest.knowledge.max-output-tokens=${VIDINGEST_KNOWLEDGE_MAX_OUTPUT_TOKENS:4096}
vidingest.knowledge.max-input-chars-per-batch=${VIDINGEST_KNOWLEDGE_MAX_INPUT_CHARS_PER_BATCH:16000}
vidingest.knowledge.max-units-per-video=${VIDINGEST_KNOWLEDGE_MAX_UNITS_PER_VIDEO:300}
vidingest.knowledge.min-salience=${VIDINGEST_KNOWLEDGE_MIN_SALIENCE:0.2}
vidingest.knowledge.embed-content=${VIDINGEST_KNOWLEDGE_EMBED_CONTENT:true}
```
Reuses the existing Ollama daemon — just a different model. Pull it once with
`ollama pull qwen2.5:14b-instruct` (or whatever model you configure).

### Docker-compose sidecars
- `diarize-asr` (built from `compose/infra/diarize-asr/`, port 9001) —
  see `compose/infra/infra.yml`. Pinned deps: `pyannote.audio==3.3.2`,
  `huggingface_hub==0.25.2` (newer drops the `use_auth_token` kwarg pyannote still calls),
  `matplotlib==3.9.2` (pyannote transitively imports it but doesn't declare it).
- `paddleocr-server` (built from `compose/infra/paddleocr-server/`, port 8002) —
  see `compose/infra/infra.yml`. Pinned dep: `numpy==1.26.4` (paddleocr 2.9.1 rejects
  numpy 2.x).

Both build contexts are project-directory relative (Compose resolves them from cwd, not
the compose file location). Tradey always invokes `docker compose` from the repo root.

Tradey wiring (`scripts/tradey.sh`):
- Both are members of the `sidecars` group — `./scripts/tradey.sh start sidecars` brings
  them up. They are deliberately *not* `depends_on` of `vidingest`, so the server starts
  without them; the matching `VIDINGEST_OCR_ENABLED` / `VIDINGEST_DIARIZATION_ENABLED`
  flags gate whether it calls them.
- `ollama` is part of the `infra` group and starts automatically with `vidingest`.
- Probe endpoints: `:9001/health` and `:8002/health` respectively.

### Ollama memory tuning

Knowledge extraction (chat model, e.g. `qwen2.5:14b-instruct` ~9 GB) runs alongside the
embed model (`gte-qwen2-1.5b-embed-f16` ~3.6 GB). Defaults in
`compose/infra/infra.yml`:

```yaml
mem_limit: 16G          # bumped from 4G for the 14b chat model
mem_reservation: 4G
environment:
  - OLLAMA_KEEP_ALIVE=30s   # unload model after 30s idle so chat and embed alternate
```

With `OLLAMA_KEEP_ALIVE=30s`, the chat model unloads soon after each batch so the embed
call can load without hitting the OOM heuristic (`"model requires more system memory than
is currently available"`). Trade-off: ~10–30 s reload cost per swap.

### Ollama embeddings client gotchas

`OllamaEmbeddingsClient` reads the `/api/embed` response as raw `String` then Jackson-decodes:

- Some Ollama versions / reverse proxies return `Content-Type: application/octet-stream`
  for the embed endpoint, which makes Spring `RestClient.body(EmbedResponse.class)` blow
  up on converter mismatch. Raw-then-parse sidesteps it.
- `EmbedResponse` carries `@JsonIgnoreProperties(ignoreUnknown = true)` so Ollama's
  bookkeeping fields (`total_duration`, `load_duration`, `prompt_eval_count`, ...) don't
  trip Jackson's strict mode.

### Per-phase rerun endpoint

Once a video is past PERSIST, individual phases can be re-run via
`POST /api/v1/videos/{videoId}/phases/{phase}/run`. Useful after a model swap or sidecar
upgrade — no need to re-download / re-transcribe. See
[Per-Phase Rerun](VidIngest%20Console%20-%20Per-Phase%20Rerun.md).

## Concurrency and run-status aggregation

A pipeline run fans out one item per URL onto `vidingestIngestionExecutor`, gated to
`vidingest.ingestion.concurrency` at a time. Items run truly concurrently, which is what makes
the run-status bookkeeping a concurrency problem.

**Run status is derived, not assigned.** `RunAggregationService.refreshRunState` recomputes the
run from its items every time an item reaches a terminal status: any item still
PENDING/IN_PROGRESS keeps the run IN_PROGRESS, any FAILED item fails the run, all-CANCELLED
cancels it, otherwise COMPLETED.

**It takes the run row with `SELECT ... FOR UPDATE` before reading the items**
(`PipelineRunRepository.findWithLockById`). That ordering is load-bearing. Without it, two
items finishing at once each read the item list and the slower one wrote its stale conclusion
over the other's, stranding the run at IN_PROGRESS with every item COMPLETED — and nothing
re-runs the aggregation once all items are terminal, so it stayed wrong until someone noticed.

The lock is scoped to `refreshRunState` alone. `ensureRunInProgress` and `updateRunPhase` fire
on every phase transition of every item and must *not* take it: every audit-event insert
already holds `FOR KEY SHARE` on the run row through its FK, which a plain `UPDATE`
(`FOR NO KEY UPDATE`) tolerates and `FOR UPDATE` does not. Their own clobber risk is handled by
`@DynamicUpdate` on `PipelineRun`, which stops a phase-only write from rewriting `status`.

### Reconcilers

| Component | Trigger | What it does |
|-----------|---------|--------------|
| `StuckItemReconciler` | `@Scheduled`, every `vidingest.reconciler.intervalMs` | Fails items PENDING or IN_PROGRESS whose `phase_updated_at` is older than `itemStaleAfter` **and** which no process claims, then re-derives their run |
| `ProgressPipelineRunReconciler` | `ApplicationReadyEvent` | Re-derives every IN_PROGRESS run from its items; only fails the ones still genuinely in progress and unclaimed |

`ProgressPipelineRunReconciler` re-derives first on purpose: a run left IN_PROGRESS by a lost
aggregation update has all its items COMPLETED, and failing that run is the bug rather than the
fix. It therefore also self-heals rows stranded before the lock landed.

`StuckItemReconciler` asks who owns the item before failing anything, and that check — not
`itemStaleAfter` — is what makes it safe. `phase_updated_at` moves only on a phase *transition*,
so a phase that legitimately runs for hours (KNOWLEDGE and DIARIZE both do on a long video) is
indistinguishable by timestamp from abandoned work. Failing a live item is not cosmetic: the
worker later flips it back to COMPLETED, and an operator who sees FAILED and retries gets a
second worker wipe-and-repopulating the same video alongside the first.

Ownership has two answers, because they fail in opposite directions:

- `PipelineService.isItemOwned` — items this JVM has claimed, queued behind the concurrency gate
  as well as executing. Blind to other instances, never wrong about this one.
- `RunItemLeaseService` (`lease_owner` / `lease_expires_at`, renewed by a heartbeat) — visible to
  every instance, but goes stale if the owner stops heartbeating, which is the signature of a
  process that died.

An item is abandoned only when neither claims it. The sweep covers `PENDING` because an item
whose owner died while it was still queued never reaches `IN_PROGRESS` — and nothing else in the
system would look at it again, leaving its run `IN_PROGRESS` forever with no retry path. That is
also why the ownership set is claimed *before* the executor submit while the lease is taken after
the gate: a queued item has not started and holds no lease, so the lease alone would call it
abandoned.

`itemStaleAfter` therefore no longer bounds how long a healthy phase may run, and the `PT1H`
default is safe to leave alone. It does bound how long an orphan waits before becoming
retryable — recovery after a restart is not immediate.

**Retry eligibility** follows the same question rather than a status match: `POST
/pipelines/{id}/retry` re-runs any item that is not COMPLETED, not CANCELLED (a deliberate
terminal state for duplicate videos), and not currently claimed. The run itself must still be
FAILED — that is a separate, run-level gate answered first, and a retry that accepts no items
leaves the run FAILED rather than moving it out of the only state from which it can be retried.

## Related pages

- [VidIngest Console](VidIngest%20Console.md)
- [VidIngest Console - Download Pipeline](VidIngest%20Console%20-%20Download%20Pipeline.md)
- [VidIngest Console - Data Model](VidIngest%20Console%20-%20Data%20Model.md)
- [VidIngest Console - Knowledge Extraction](VidIngest%20Console%20-%20Knowledge%20Extraction.md)
- [VidIngest Console - Per-Phase Rerun](VidIngest%20Console%20-%20Per-Phase%20Rerun.md)
