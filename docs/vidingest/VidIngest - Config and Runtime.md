# VidIngest - Config and Runtime

- **Primary packages**: `com.tradinglabs.vidingest.config`
- **Last reviewed**: 2026-08-27
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
| `spring.threads.virtual.enabled` | `true` | Use virtual threads (Java 26) |

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
| `spring.jpa.open-in-view` | `false` | - | Off on purpose: OSIV holds a connection for the whole request against a Hikari pool of 10 that the pipeline competes for. Safe because every controller returns a MapStruct DTO mapped inside the service transaction, so no lazy association reaches the serializer |
| `spring.liquibase.enabled` | `true` | - | Run migrations on startup |

No `spring.jpa.properties.hibernate.dialect`: Hibernate selects `PostgreSQLDialect` from the
connection, and warns (`HHH90000025`) if it is named anyway.

### Storage (`vidingest.storage.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `vidingest.storage.video-path` | Auto-resolved by `ProjectPathResolver` | Directory for downloaded videos |

**Path resolution priority**:

1. Explicit `vidingest.storage.video-path` (if set and non-empty). The `docker` profile always
   sets it to `/data/videos`, so nothing below runs in a container.
2. Otherwise `ProjectPathResolver` builds `{projectRoot}/package/vidingest/videos`, resolving
   `projectRoot` in this order:
   1. `VIDEO_KNOWLEDGE_ROOT` env var, if it names an existing directory
   2. Walking up from `user.dir` (max 10 levels) for the root `pom.xml`
   3. Fallback: `user.dir`, with a `WARN` on each of the two lines it logs

Compose sets `VIDEO_KNOWLEDGE_ROOT=/app` for exactly that last case — the runtime image ships
no `pom.xml`, so the walk always failed and warned twice on every boot.

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
| `compose/infra/infra.yml` | `postgres`, `ollama`, `whisper`, `paddleocr-server`, `diarize-asr` |
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
| `postgres_data` | `/var/lib/postgresql/data` | `postgres` |
| `ollama_data` | `/root/.ollama` | `ollama` |
| `ai_models` | `/models` | shared model cache for `ollama`, `whisper` (`ASR_MODEL_PATH=/models/whisper`), `paddleocr-server`, `diarize-asr` |

Transcript sidecars are written next to the downloaded video file, so under
`/data/videos/...` in a container.

### Dockerfile (`vidingest-server`)

- Build stage: Eclipse Temurin 26 JDK Alpine + Maven 3.9.11 (via `docker/scripts/install-maven.sh`)
- Runtime stage: Eclipse Temurin 26 JRE Alpine
- Extra tools: `python3`, `py3-pip`, `ffmpeg`, `deno`, plus `yt-dlp` via pip
- User: `spring:spring` (uid/gid 1000, from `docker/scripts/create-app-user.sh`)
- JVM: `-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=20.0 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC`
- Container memory: `mem_limit: 1G`, `mem_reservation: 256M`

**No `-Xmx`, deliberately.** It used to be `-Xmx768m` against a `mem_limit` of `768M`, which
also made the `MaxRAMPercentage` on the same line inert: the heap could claim every byte the
container had, while metaspace, thread stacks and the native buffers OCR uses for multipart
frame upload all live outside it. The container OOM killer therefore always beat
`-XX:+ExitOnOutOfMemoryError`, so a Java OOM could never be reported as one. The percentage is
container-aware and re-scales on its own when `mem_limit` changes. `vidingest-mcp` was always
configured this way; the server is now consistent with it.
- Health check: `GET /vidingest/api/v1/health/ready` from inside the container
- Build context is the repository root so the Maven reactor can resolve sibling modules

**There is no OpenTelemetry agent.** All three Dockerfiles used to `curl` a pinned 2.26.1
javaagent into `/otel` — 24 MB per image, downloaded on every uncached build, and never loaded:
the entrypoints are a plain `java -jar /app/app.jar`, confirmed from `/proc/1/cmdline`. There is
no collector in the compose stack for it to export to either, so it was removed rather than
wired. To add tracing, add a collector service first, then `-javaagent` plus `OTEL_*` config —
re-adding the download alone just restores the dead weight.

Metrics do not depend on it: `/actuator/prometheus` carries
`vidingest.pipeline.phase.duration{phase,outcome}`, `vidingest.pipeline.items.*`,
`http.server.requests` and the Hikari pool gauges.

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
[Knowledge Extraction](VidIngest%20-%20Knowledge%20Extraction.md).

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

### PaddleOCR memory, and why OCR time tracks text density

OCR cost is charged **per detected text line**, not per frame, because recognition runs once per
detected box. Measured on one 157s video: a 4-line frame took 0.5s, a 141-line chart frame took
17-19s, and the phase as a whole did 560 lines across 17 frames.

That makes `paddleocr-server`'s memory limit load-dependent, and it was set too low. At
`mem_limit: 3G` a text-dense frame put the container permanently in direct reclaim:

```
anon                     2.88G   against a 3G cap
memory.events max        27179   hard-limit hits
pgscan_direct            12.2M   pages scanned in synchronous reclaim
workingset_refault_anon   3.8M   evicted, then faulted straight back
oom_kill                     0   ← never failed, so it only looked like latency
```

Direct reclaim is charged to the allocating thread, so it landed on request latency and showed up
as wild variance: the *same* frame took anywhere from 29s to 56s. The limit is now
`${PADDLEOCR_MEM_LIMIT:-6G}`, at which every counter above reads zero and that frame settles at
17-19s. Peak anon is 5.5G, so 6G is right-sized rather than generous — raise
`PADDLEOCR_MEM_LIMIT` for denser sources, and check `memory.events` before blaming the CPU:

```bash
docker exec video-knowledge-paddleocr-server-1 \
  sh -lc 'grep -E "^(anon|pgscan_direct|workingset_refault_anon) " /sys/fs/cgroup/memory.stat; cat /sys/fs/cgroup/memory.events'
```

Two things measured and rejected while tuning this. **Parallelising the per-frame loop**: four
concurrent sidecar requests took 2.11s against 2.04s for four sequential ones — it is one uvicorn
worker doing CPU-bound work, so concurrency buys nothing until the sidecar runs more workers, and
each worker costs another ~5G. **`OCR_USE_ANGLE_CLS=false`**: it looked like a 30-40% win while the
container was thrashing, but at 6G on/off measured 16.9-19.3s against 17.5-19.9s — the apparent
gain was the reclaim, not the flag.

`vidingest.ocr.max-results-per-video` (default 10000, `VIDINGEST_OCR_MAX_RESULTS_PER_VIDEO`) is a
real ceiling on the work, not a post-hoc trim — `OcrService` tests it before each sidecar call and
stops. At the ~0.28s per line above, though, 10000 rows is still ~45 minutes for a single video, so
lower it for text-dense sources rather than relying on it as a safety net.

### Ollama memory tuning

Knowledge extraction (chat model, e.g. `qwen2.5:14b-instruct` ~9 GB) runs alongside the
embed model (`gte-qwen2-1.5b-embed-f16` ~3.6 GB). Defaults in
`compose/infra/infra.yml`:

```yaml
mem_limit: 16G          # bumped from 4G for the 14b chat model
mem_reservation: 4G
environment:
  - OLLAMA_KEEP_ALIVE=${OLLAMA_KEEP_ALIVE:-30s}   # unload after 30s so chat and embed alternate
```

With `OLLAMA_KEEP_ALIVE=30s`, the chat model unloads soon after each batch so the embed
call can load without hitting the OOM heuristic (`"model requires more system memory than
is currently available"`). Trade-off: ~10–30 s reload cost per swap.

**That default only pays for itself while KNOWLEDGE is on.** With it off, the embed model is
the only resident model, there is nothing to swap with, and the unload is pure cost — measured
on a 19 s video, the CONTEXT phase took **57.8 s cold against 2.4 s warm**, nearly all of it
reloading 3.2 GB (12.1 s of that when the file was still in the host page cache; the rest is a
cold read off disk). Raise it in the gitignored `.env` at the repo root for that case:

```bash
OLLAMA_KEEP_ALIVE=4h
```

Drop it back to `30s` before enabling KNOWLEDGE — `qwen2.5:14b` (~9 GB) plus the embed model
(~3.2 GB) needs the churn.

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
[Per-Phase Rerun](VidIngest%20-%20Per-Phase%20Rerun.md).

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

- [VidIngest](VidIngest.md)
- [VidIngest - Download Pipeline](VidIngest%20-%20Download%20Pipeline.md)
- [VidIngest - Data Model](VidIngest%20-%20Data%20Model.md)
- [VidIngest - Knowledge Extraction](VidIngest%20-%20Knowledge%20Extraction.md)
- [VidIngest - Per-Phase Rerun](VidIngest%20-%20Per-Phase%20Rerun.md)
