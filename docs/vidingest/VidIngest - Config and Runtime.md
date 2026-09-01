---
type: reference
last_reviewed: 2026-08-29
---

# VidIngest - Config and Runtime

- **Primary packages**: `com.tradinglabs.vidingest.config`
- **Last reviewed**: 2026-09-01
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

### Transcription (`vidingest.transcription.*`)

Renamed from `vidingest.whisper.*` (Aug 2026) when TRANSCRIBE stopped being tied to
openai-whisper-asr-webservice. Code pointers:
[TranscriptionClientProperties.java](../../applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/config/TranscriptionClientProperties.java),
[core/transcription/client/](../../applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/core/transcription/client).

| Property | Default | Description |
|----------|---------|-------------|
| `vidingest.transcription.provider` | `whisper-asr` (base), `openai-compatible` (docker/dev) | Wire protocol: `whisper-asr` posts `{base}/asr?output=json` with an `audio_file` part; `openai-compatible` posts `{base}/audio/transcriptions` with `file` + `model` + `response_format=verbose_json` |
| `vidingest.transcription.base-url` | `http://localhost:9000` (base), `${VK_HOST_LLM_URL}` (docker) | Must include the API prefix (usually `/v1`) for `openai-compatible` |
| `vidingest.transcription.model` | `whisper-1` | Required by `openai-compatible`; ignored by whisper-asr-webservice, whose model is fixed by the image's `ASR_MODEL` |
| `vidingest.transcription.api-key` | *(empty)* | Sent as `Authorization: Bearer` whenever set, for either provider |
| `vidingest.transcription.prompt` | *(empty)*; compose sets a trading vocabulary | Decoder vocabulary hint, sent as the `prompt` part by `openai-compatible` only — the whisper-asr sidecar's `/asr` has no equivalent. See below |
| `vidingest.transcription.connect-timeout` | `5s` | Startup-bound — not editable through the connections API |
| `vidingest.transcription.read-timeout` | `30m` | Startup-bound |

The client always sends `response_format=verbose_json` on the OpenAI path: the spec only promises
segments for that format, and the phase persists one row per segment. Individual servers are looser
— oMLX returns segments for plain `json` too — which is why the request asks explicitly instead of
depending on which server is answering. Both providers return the same envelope shape
(`{text, language, segments[{start,end,text}]}`), which is why one parser in
`AbstractTranscriptionClient` serves both.

The docker profile used to hardcode `http://whisper:9000` with no `${...}` indirection, so
`VIDINGEST_WHISPER_BASE_URL` had never had any effect under compose. The replacement
`VIDINGEST_TRANSCRIPTION_BASE_URL` does.

**`vidingest.transcription.prompt` is worth setting, and is not an instruction.** The OpenAI audio
API's `prompt` conditions the decoder, so it belongs in the same register as the speech: a
comma-separated list of terms and spellings the audio is likely to contain, never a sentence.

Measured on a 3-minute trading short against `whisper-large-v3-turbo`, 3 runs per arm, both fully
deterministic — **9 wrong-form occurrences became 3**:

| term | without the hint | with it |
|---|---|---|
| *break of structure* | 12 correct, **3× "breaker structure"** | **15 correct, 0 wrong** |
| garbled *"where we can will close"* | 3× | **0** |
| *fib retracement* | 3 correct, 3× "fiber tracement" | 3 correct, 3× **"fiber retracement"** — still wrong |

Confirmed live: re-running TRANSCRIBE on that video produced the 3783-char transcript with
`break of structure` ×5 and no `breaker structure`, and re-running FUSE + KNOWLEDGE after it left
**zero** ASR-error terms in the knowledge units.

Why this is worth more than a transcript typo normally would be: the KNOWLEDGE prompt tells the
model to reproduce the speaker's terminology **verbatim**
([Knowledge Extraction](VidIngest%20-%20Knowledge%20Extraction.md)), so a mangled domain term
propagates by design into the units, their embeddings and search. It was observed doing so before
this was set.

Two cautions. It is **empty by default** because a whisper prompt is a documented hallucination
vector — the decoder will happily continue a prompt that does not match the audio, so set it to the
vocabulary of the channels actually being ingested and to nothing else. And listing a term does not
guarantee it: `fib retracement` was in the hint and still came back as `fiber retracement`, so a
short surface form can simply lose to a common English word. Unsolved.

#### Getting an ASR model into oMLX

Two traps, both hit while setting this up. Neither is guessable from the error alone.

- **`mlx-community/whisper-*` repos ship only `config.json` + `weights.safetensors`** — the older
  mlx-whisper layout. oMLX refuses to load them:
  *"missing the HuggingFace processor / feature-extractor configuration"*. The weights are fine; only
  the tokenizer/processor JSON is absent. Copy it from the upstream OpenAI repo into the model
  directory and `POST /admin/api/reload`:

  ```bash
  D=~/.omlx/models/mlx-community/whisper-large-v3-turbo
  for f in preprocessor_config.json tokenizer.json special_tokens_map.json \
           tokenizer_config.json added_tokens.json normalizer.json generation_config.json; do
    curl -sL -o "$D/$f" "https://huggingface.co/openai/whisper-large-v3-turbo/resolve/main/$f"
  done
  ```

- **Do not reach for Parakeet.** oMLX detects `mlx-community/parakeet-tdt-0.6b-v3` and loads it, but
  mlx-audio's Parakeet returns `AlignedResult.sentences` while oMLX's STT engine reads
  `getattr(result, "segments")` — so every transcription comes back with an **empty** segment list.
  TRANSCRIBE persists one row per segment, so the phase would store a bare blob and FUSE would have
  nothing to align against. Whisper is the only supported family that emits
  `{"start", "end", "text"}`.

Measured on this box with `whisper-large-v3-turbo` (1.6 GB): 27.7 s for the first call including the
model load, then **1.65 s for 5.3 s of audio** warm — roughly 3× realtime on the GPU, against a
CPU-only `ASR_MODEL=small` container before.

#### End-to-end, all ten phases (2026-08-29)

One ingest of a 19 s video through the compose stack, every optional phase on, the three model
connections pointed at the host and the two sidecars in containers. **73 s wall clock**, run
`COMPLETED`, all ten phases entered and completed in the audit trail.

| Phase | Result |
|---|---|
| TRANSCRIBE | 5 segments, `language=en`, **2.3 s** for 19 s of audio via `host.docker.internal:8000/v1/audio/transcriptions` |
| DIARIZE | 2 speakers, assigned to 5/5 segments |
| FRAME_SAMPLE | 2 frames |
| OCR | ran on both frames, 0 results — correct, the source has no on-screen text |
| FUSE | 1 multimodal segment |
| KNOWLEDGE | 1 unit, **24 s** on `Qwen2.5-14B-Instruct-4bit` |
| CONTEXT | 1 chunk, embedding non-null |

The KNOWLEDGE number is the point of the whole move: the same 14B model in the CPU-only container
took 384 s just to load and then **timed out at 600 s on one batch**. Semantic search over the
result returns the chunk for *"what animal has a long trunk"* against a transcript that says
*"long fronts"* — no shared keyword, so the 1536-wide vector really is doing the work.

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

When running VidIngest via the Docker stack (`SPRING_PROFILES_ACTIVE=docker`), semantic search is
enabled by default and embeddings use the **OpenAI-compatible** client against the model runtime on
the host. Switch `VIDINGEST_EMBEDDINGS_PROVIDER` to `ollama` to talk to an ollama daemon instead —
on the host or on another machine, since there is no ollama container any more.

- `VIDINGEST_SEARCH_SEMANTIC_ENABLED=true`
- `VIDINGEST_EMBEDDINGS_PROVIDER=openai-compatible`
- `VIDINGEST_EMBEDDINGS_BASE_URL=${VK_HOST_LLM_URL}` → `http://host.docker.internal:8000/v1`
- `VIDINGEST_EMBEDDINGS_MODEL=Qwen3-Embedding-4B-4bit-DWQ` — must name a model the host serves
  **as an embedding model**; see the oMLX note below, which is not obvious
- `VIDINGEST_EMBEDDINGS_EXPECTED_DIMENSIONS=1536` — the pgvector column is `VECTOR(1536)`, checked
  on every response
- `VIDINGEST_EMBEDDINGS_DIMENSIONS=1536` — the OpenAI `dimensions` request field (Matryoshka
  truncation). Sent only when set; leave it unset for a model that is natively the right width,
  because not every OpenAI-compatible server tolerates the field
- `VIDINGEST_EMBEDDINGS_TIMEOUT=180s` — one var; the old `VIDINGEST_OLLAMA_TIMEOUT` used to
  shadow it even when the provider was `openai-compatible`
- `VIDINGEST_OLLAMA_BASE_URL=http://host.docker.internal:11434` and
  `VIDINGEST_EMBEDDINGS_OLLAMA_MODEL=...` — used only when the provider is switched back to `ollama`

Notes:

- The base URL must be reachable **from inside the container**. `host.docker.internal` resolves to
  the host (compose adds the matching `extra_hosts` entry so it works on Linux too), but the host
  process has to **bind `0.0.0.0`**: a container arrives on the bridge address, so a
  `127.0.0.1`-only listener refuses the connection. This is the single most common cause of
  "connection refused" after the move off the `llm` container.
- Every one of these is a *starting* value. All five connections are editable at runtime — see
  [Connections API](#connections-api-runtime-editable) below.
- **oMLX decides a model is an embedding model by its directory name.** `_is_causal_lm_embedding`
  looks for `embed`/`embedding` in the name, because a causal-LM embedder's `config.json` is
  identical to a plain LLM's. So `gte-Qwen2-1.5B-instruct-MLX-Q8` — natively 1536-wide, exactly the
  column's width — is filed as an `llm` and answers `/v1/embeddings` with
  *"Model ... is not an embedding model"*. Forcing it with `model_type_override: "embedding"` does
  not help either: oMLX's embedding engine supports `bert`, `modernbert`, `xlm_roberta`, `qwen3`
  and `gemma3_text`, **not `qwen2`**, so the load fails with *"Model type qwen2 not supported"*.
- **No oMLX-supported embedding architecture is natively 1536-wide**, which is why
  `VIDINGEST_EMBEDDINGS_DIMENSIONS` exists. `Qwen3-Embedding-4B` is 2560 and Matryoshka-trained, so
  the server truncates to 1536 on request. Truncation only ever goes **down** — asking the 1024-wide
  `Qwen3-Embedding-0.6B` or `bge-m3` for 1536 fails at the server. Measured on this box: 2560
  native, 1536 with the field, unit-normalised, cosine 0.49 between two distinct inputs.
- Semantic search results depend on context-chunk generation during ingestion; ingest without `TRANSCRIBE` or `CONTEXT` in `skipPhases`.
- For videos ingested before semantic search was enabled, regenerate context chunks via `POST /vidingest/api/v1/videos/{videoId}/context/regenerate`.

#### Troubleshooting (semantic search)

- **409 Conflict: semantic search disabled**
  - Cause: `vidingest.search.semantic-enabled=false`.
  - Fix: set `VIDINGEST_SEARCH_SEMANTIC_ENABLED=true`.
- **409 Conflict: no query embedding provider configured**
  - Cause: embeddings provider is misconfigured or inactive.
  - Fix (Ollama): set `VIDINGEST_EMBEDDINGS_PROVIDER=ollama` and configure `VIDINGEST_OLLAMA_BASE_URL` + `VIDINGEST_EMBEDDINGS_OLLAMA_MODEL`.
  - Or fix it without a restart: `PUT /api/v1/connections/EMBEDDINGS`, then `POST /api/v1/connections/EMBEDDINGS/test` to confirm.
  - Fix (OpenAI-compatible): set `VIDINGEST_EMBEDDINGS_PROVIDER=openai-compatible` and configure `VIDINGEST_EMBEDDINGS_BASE_URL` + `VIDINGEST_EMBEDDINGS_MODEL` (+ `VIDINGEST_EMBEDDINGS_API_KEY` if needed).
- **Upstream embeddings failure**
  - Cause: embeddings call failed (network, auth, model mismatch, timeout).
  - Fix: verify provider URLs, model name, and connectivity from inside the container; increase timeout via `VIDINGEST_EMBEDDINGS_TIMEOUT`.

## Connections API (runtime-editable)

Everything above is bound from the environment at startup. The five *connections* — where the
server reaches its model runtimes and sidecars — are additionally editable while it runs, from
`GET/PUT/DELETE /api/v1/connections/{name}` or the console's **Settings** screen.

Code:
[connections/](../../applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/connections),
changeset `008-connections.sql`, console
[features/settings/](../../applications/webapp/src/app/features/settings).

| Name | Config bean | Providers | Model? | Enable toggle? |
|------|-------------|-----------|--------|----------------|
| `EMBEDDINGS` | `vidingest.search.embeddings.*` | `ollama`, `openai-compatible`, `disabled` | yes | no |
| `KNOWLEDGE` | `vidingest.knowledge.*` | `ollama`, `openai-compatible` | yes | yes |
| `TRANSCRIPTION` | `vidingest.transcription.*` | `whisper-asr`, `openai-compatible` | yes | no |
| `DIARIZATION` | `vidingest.diarization.*` | `diarize-asr` | no | yes |
| `OCR` | `vidingest.ocr.*` | `paddleocr` | no | yes |

| Method | Path | Notes |
|--------|------|-------|
| `GET` | `/api/v1/connections` | All five, with their effective values |
| `GET` | `/api/v1/connections/{name}` | |
| `PUT` | `/api/v1/connections/{name}` | Stores the override **and** applies it immediately |
| `DELETE` | `/api/v1/connections/{name}` | 204; reverts to the value the server started with |
| `POST` | `/api/v1/connections/{name}/test` | Always 200 — an unreachable dependency is a successful answer, carried as `reachable: false` |

```bash
curl -s localhost:8051/vidingest/api/v1/connections | jq '.[] | {name, provider, baseUrl, overridden}'
```

```bash
curl -s -XPUT localhost:8051/vidingest/api/v1/connections/KNOWLEDGE -H 'Content-Type: application/json' -d '{"provider":"openai-compatible","baseUrl":"http://host.docker.internal:8000/v1","model":"Qwen2.5-14B-Instruct-4bit","enabled":true}'
```

Things worth knowing before you rely on it:

- **A row is an override, not the configuration.** Absent, the environment value applies. The
  service snapshots the environment values at startup *before* applying rows, which is the only
  reason `DELETE` can mean "back to what `.env` said" — nothing else in the process remembers them.
- **It takes effect without a restart** because the `@ConfigurationProperties` beans are mutable
  singletons and every client resolves its base URL per call. The four `RestClient` beans therefore
  carry **no** `.baseUrl(...)`; adding one back silently re-pins the URL to startup.
- **Timeouts are not exposed.** They are consumed once when each request factory is built, so a
  value shown here would not be the one in use. Change them in the environment and restart.
- **API keys are write-only.** `GET` returns `hasApiKey`, never the key. On `PUT`, an absent
  `apiKey` keeps the stored one and `""` clears it — without that distinction the console could not
  save any other field without wiping the key. At rest the column is plaintext; encrypt it if this
  database ever leaves the operator's own host.
- **`model` is a full replacement, `apiKey` and `enabled` are not.** A `PUT` that omits `model`
  drops the model override, so the value reverts to what the environment configured — which is the
  "a field the row does not carry is not overridden" rule applied consistently, but it will surprise
  you from `curl`: changing only the base URL silently puts an ollama tag back where you had an oMLX
  model id. The console always sends the field. `apiKey` is exempt because it cannot be read back,
  and `enabled` because omitting it should leave a phase toggle alone.
- **The provider is validated on the way in**, against the same list the router uses at call time
  (served back as `supportedProviders`). An unrecognised value would otherwise fail the phase
  rather than the request.

## Docker configuration

### Compose layout

There is no single `docker-compose.yml`. `compose.yml` holds only the shared network and
the named volumes; services live in split files layered on top of it:

| File | Services |
|------|----------|
| `compose/infra/infra.yml` | `postgres`, `paddleocr-server`, `diarize-asr` |
| `compose/services.yml` | `vidingest` (REST server), `webapp` (console behind nginx) |
| `compose/cli.yml` | `vidingest-cli` |
| `compose/mcp.yml` | `vidingest-mcp` |

`scripts/tradey.sh` and `scripts/compose.sh` apply the layering; running bare
`docker compose` from the repo root sees only the empty base file. Host ports come from
`compose/ports.env` and everything publishes to `127.0.0.1` unless `VK_BIND_ADDR` says
otherwise.

**The console is a separate image.** `webapp` (nginx, `WEBAPP_PORT` 8052) serves the Angular
bundle and proxies the API to `vidingest:8051`. The server image used to build the bundle in a node
stage and bake it into `classpath:/static`, served by a `SpaStaticResourceConfig`; both were removed
once the container existed, so the vidingest image is the API alone and `http://localhost:8051/vidingest/`
no longer serves a page. One console, one place.

nginx proxies rather than the app being told where the server is, because **every request the
console makes is relative** and the server has no CORS configuration — the two must stay
same-origin. The four proxied prefixes in `applications/webapp/nginx.conf` (`api/`, `actuator`,
`v3/`, `swagger-ui`) are the server's whole surface and now live only there; a path that should
reach the server but matches none of them falls into the SPA branch and returns `index.html` with a
**200**, which reads as a blank screen rather than a 404.

**The `llm` and `whisper` services are gone** (Aug 2026). Both ran CPU-only inside the Docker VM,
which cannot reach the GPU; inference now runs as a host process and compose points at it via
`VK_HOST_LLM_URL` (default `http://host.docker.internal:8000/v1`, an oMLX server).
`GROUP_INFRA` in `tradey.sh` is therefore just `postgres`, and `tradey.sh llm` is gone with the
container it exec'd into. After upgrading, compose will report the two old containers as orphans;
clear them with `./scripts/compose.sh down --remove-orphans` and reclaim the model volume by hand
once you are sure: `docker volume rm video-knowledge_ollama_data`.

### Storage in containers

Storage is **named volumes**, not bind mounts — nothing under `package/` is mounted into
a container. `package/` is only used when the server runs on the host, where
`ProjectPathResolver` resolves `{projectRoot}/package/vidingest/videos`.

| Volume | Mounted at | Used by |
|--------|-----------|---------|
| `vidingest_data` | `/data/videos` | `vidingest` (rw) — `application-docker.properties` sets `vidingest.storage.video-path=/data/videos`; also `paddleocr-server` as `:ro` so OCR reads frames in place |
| `app_logs` | `/app/logs` | `vidingest`, `vidingest-mcp` (`LOG_DIR=/app/logs`) |
| `postgres_data` | `/var/lib/postgresql/data` | `postgres` |
| `ai_models` | `/models` | shared model cache for `paddleocr-server` and `diarize-asr` (the `llm` and `whisper` services that also used it are gone) |

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

## Knowledge-extraction properties

All disabled by default (fusion is the exception — pure-Java, defaults on). Full
description and operational notes in
[Knowledge Extraction](VidIngest%20-%20Knowledge%20Extraction.md).

### Speaker diarization
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

### Frame sampling
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

### OCR
```properties
vidingest.ocr.enabled=${VIDINGEST_OCR_ENABLED:false}
vidingest.ocr.base-url=${VIDINGEST_OCR_BASE_URL:http://localhost:8002}
vidingest.ocr.languages=${VIDINGEST_OCR_LANGUAGES:en}
vidingest.ocr.min-confidence=${VIDINGEST_OCR_MIN_CONFIDENCE:0.5}
vidingest.ocr.max-results-per-video=${VIDINGEST_OCR_MAX_RESULTS_PER_VIDEO:10000}
```

### Multimodal fusion
```properties
vidingest.fusion.enabled=${VIDINGEST_FUSION_ENABLED:true}
vidingest.fusion.window-seconds=${VIDINGEST_FUSION_WINDOW_SECONDS:30.0}
vidingest.fusion.window-overlap-seconds=${VIDINGEST_FUSION_WINDOW_OVERLAP_SECONDS:5.0}
vidingest.fusion.max-segments-per-video=${VIDINGEST_FUSION_MAX_SEGMENTS_PER_VIDEO:2000}
```

### Knowledge extraction
```properties
vidingest.knowledge.enabled=${VIDINGEST_KNOWLEDGE_ENABLED:false}
vidingest.knowledge.provider=${VIDINGEST_KNOWLEDGE_PROVIDER:ollama}
vidingest.knowledge.chat-model=${VIDINGEST_KNOWLEDGE_CHAT_MODEL:qwen2.5:14b-instruct}
vidingest.knowledge.base-url=${VIDINGEST_KNOWLEDGE_BASE_URL:${VIDINGEST_LLM_BASE_URL:http://localhost:11434}}
vidingest.knowledge.api-key=${VIDINGEST_KNOWLEDGE_API_KEY:}
vidingest.knowledge.temperature=${VIDINGEST_KNOWLEDGE_TEMPERATURE:0.2}
vidingest.knowledge.max-output-tokens=${VIDINGEST_KNOWLEDGE_MAX_OUTPUT_TOKENS:4096}
vidingest.knowledge.max-input-chars-per-batch=${VIDINGEST_KNOWLEDGE_MAX_INPUT_CHARS_PER_BATCH:16000}
vidingest.knowledge.max-units-per-video=${VIDINGEST_KNOWLEDGE_MAX_UNITS_PER_VIDEO:300}
vidingest.knowledge.min-salience=${VIDINGEST_KNOWLEDGE_MIN_SALIENCE:0.2}
vidingest.knowledge.embed-content=${VIDINGEST_KNOWLEDGE_EMBED_CONTENT:true}
```
Defaults to the host model runtime — the same server the embeddings client uses, just a different
model. The model must already exist there, under the id the runtime uses: oMLX names the same
weights `Qwen2.5-14B-Instruct-4bit` where ollama calls them `qwen2.5:14b-instruct`.

```bash
curl -s http://localhost:8000/v1/models | jq '.data[].id'
```

`provider` selects the wire protocol: `ollama` (`/api/chat`) or `openai-compatible`
(`/chat/completions`). Use the latter for oMLX, LM Studio, `llama-server`, mlx-lm, vLLM, or a
remote host — and give `base-url` the `/v1` suffix those servers expect:

```properties
VIDINGEST_KNOWLEDGE_PROVIDER=openai-compatible
VIDINGEST_KNOWLEDGE_BASE_URL=http://host.docker.internal:8000/v1
VIDINGEST_KNOWLEDGE_API_KEY=            # only hosted endpoints need one
```

`KnowledgeChatClientRouter` reads `provider` **per call**, because it is runtime-editable through
`PUT /api/v1/connections/KNOWLEDGE`. An unrecognised value therefore fails the KNOWLEDGE phase
rather than the context at startup, which is what it used to do — the API validates it on the way
in so the typo is still caught before any run. There is no `Disabled` chat client, since
`vidingest.knowledge.enabled=false` already turns the phase off.

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
- The model runtimes are **not** compose services at all — see
  [Connections API](#connections-api-runtime-editable). `infra` is postgres alone.
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

### Model-runtime memory (host, not compose)

The old section here tuned `OLLAMA_KEEP_ALIVE` and `mem_limit` on the `llm` container, where the
chat model (~9 GB) and the embed model (~3.2 GB) fought over the Docker VM's headroom and a 30 s
keep-alive was the compromise that stopped the OOM heuristic firing
(`"model requires more system memory than is currently available"`). It measured a CONTEXT phase at
**57.8 s cold against 2.4 s warm**, nearly all of it reloading the embed model.

None of that applies now: the runtime is a host process with the whole machine's unified memory and
the GPU, and it manages its own residency. What is left of the trade-off is the same in shape —
loading a second model can evict the first — so if a phase suddenly gets slow, check what the host
is holding. For oMLX:

```bash
curl -s http://localhost:8000/api/status | jq '{loaded_models, models_loaded, models_loading}'
```

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

## Failure modes

| Symptom | Cause | Fix |
|---------|-------|-----|
| `relation "vidingest_videos" does not exist` | Liquibase migration not run | Check `spring.liquibase.enabled=true` and DB connectivity |
| `yt-dlp failed with exit code` | yt-dlp not installed or network issue | Install yt-dlp (`pip install yt-dlp`), check network |
| `yt-dlp timed out after N seconds` | Command exceeded configured timeout | Increase `vidingest.download.timeout-seconds` or disable timeout with `0` |
| `... transcription request failed` / `TRANSCRIPTION_FAILURE` | Transcription runtime unreachable, wrong provider for the URL, no ASR model on the host, or ffmpeg missing | `POST /api/v1/connections/TRANSCRIPTION/test`. If the host binds `127.0.0.1` only, a container cannot reach it — bind `0.0.0.0`. On `openai-compatible`, the base URL must end in `/v1` and the `model` must be one the host serves |
| `Connection refused` on startup | PostgreSQL not running | Start PostgreSQL on the configured host/port |
| `Ingestion failed: Video already ingested` | Duplicate video URL | Expected behavior; the same source+videoId pair cannot be ingested twice |
| `Semantic search is disabled` | Search feature flag off | Set `VIDINGEST_SEARCH_SEMANTIC_ENABLED=true` and configure embeddings (see Semantic search section above) |

Moved here from the overview page: this is runtime behaviour, and the overview is a routing file.

## Related pages

- [VidIngest](VidIngest.md)
- [VidIngest - Download Pipeline](VidIngest%20-%20Download%20Pipeline.md)
- [VidIngest - Data Model](VidIngest%20-%20Data%20Model.md)
- [VidIngest - Knowledge Extraction](VidIngest%20-%20Knowledge%20Extraction.md)
- [VidIngest - Per-Phase Rerun](VidIngest%20-%20Per-Phase%20Rerun.md)
