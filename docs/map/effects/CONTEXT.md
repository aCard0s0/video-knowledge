# effects — "I am changing X, open these"

One job: name the cards to read **before** an edit. It holds no waterfalls of its own — if this
index and a card disagree, **fix the card**, then fix the row.

## Inputs
- Working: what you are about to change
- Reference: [`../objects/_index.md`](../objects/_index.md)

## Process
1. Find the row. 2. Open the cards it names. 3. Edit.

## Human check
After the change, did anything break that no row predicted? Add it to the card that owns it, then
add the row.

---

## Changing the pipeline

| Changing | Open |
|---|---|
| adding or removing a **phase** | [PipelineRunPhase](../objects/run/pipeline-run-phase.md) — the five places, and the ones it deliberately does not touch |
| **phase order** | [PipelineRunPhase](../objects/run/pipeline-run-phase.md) + [ingest-run](../processes/ingest-run.md). Order is the registry constructor, not the enum |
| **run or item status** writes | [PipelineRun](../objects/run/pipeline-run.md) — one writer, `RunAggregationService`. Do **not** widen the `FOR UPDATE` past `refreshRunState` |
| **lease, heartbeat, or reconciler timings** | [reap](../processes/reap.md) + [PipelineRunItem](../objects/run/pipeline-run-item.md). `heartbeatMs` must stay well under `lease.ttl` |
| **retry or rerun** behaviour | [re-execute](../processes/re-execute.md) — absent `skipPhases` is not empty |
| an **audit event type** | [PipelineRunItemEvent](../objects/run/pipeline-run-item-event.md) + `core/domain.ts`. The console's 500/4-page tail mirrors a server clamp by hand |

## Changing data

| Changing | Open |
|---|---|
| any **entity or column** | that noun's card, then [Video](../objects/media/video.md) for the cascade |
| the **schema** | a **new numbered changeset file** plus an include in `db.changelog-master.yaml`. Never edit an applied changeset. The Aug 2026 consolidation that rewrote ids and checksums worked only because the DB was recreated from backup — it is not repeatable |
| adding an **index** | the card first. Two rules the schema has already broken: no single-column index on the leftmost column of an existing composite or unique index, and no GIN on any `metadata` column until a query needs it |
| a **speaker label** | [Speaker](../objects/derived/speaker.md) + [MultimodalSegment](../objects/derived/multimodal-segment.md). The label array has **no FK** — nothing will tell you |
| **embedding width** | [ContextChunk](../objects/derived/context-chunk.md) + [KnowledgeUnit](../objects/derived/knowledge-unit.md). 1536 is a column type, not a config value |
| **deleting a video** | [Video](../objects/media/video.md). Row first, artifacts after the commit |

## Changing the API or the console

| Changing | Open |
|---|---|
| adding an **endpoint** | [regenerate-client](../processes/regenerate-client.md) — path constant, `operationId`, `api:gen`, and the manual `domain.ts` pass |
| a **server enum constant** | [domain.ts enums](../objects/console/domain-enums.md). Nothing fails; the console just stops recognising the value |
| an **error body** | [ApiFailure](../objects/console/api-failure.md). Three responses do not look like failures: 400-with-a-body on create, 503-with-the-report on readiness, 202-carrying-`REJECTED` on retry |
| anything the **lane screens** draw | [watchRun](../objects/console/run-watch.md). Two screens, one file — that is why it exists |
| a **colour** | `applications/webapp/src/styles/_tokens.scss`. Never a raw hex in a component; two measured themes, not an inversion |

## Changing the build or the runtime

| Changing | Open |
|---|---|
| an **ffmpeg call** | it goes through `FfmpegRunner`. Never `readAllBytes()` before `waitFor` — the stream reaches EOF only when the process exits |
| a **transaction boundary** | `@Transactional` on a `protected`/`private`/self-invoked method does nothing. Never wrap a sidecar, LLM or yt-dlp call — the pool is 10. `SubprocessTransactionBoundaryIntegrationTest` and `ContextChunkRegenerateIntegrationTest` assert this from inside the stubbed call |
| the **postgres image** | must equal the Testcontainers tag in `BaseVidingestIntegrationTest` (`pgvector/pgvector:pg17`). Staying on pg17 is also a volume-mount constraint — pg18 relocated PGDATA |
| an **LLM provider** | [Connection](../objects/runtime/connection.md). A **router** picks per call — not `@ConditionalOnProperty`, because the value is runtime-editable. Provider-named classes speak that wire protocol; every neutral surface is named for the role |
| a **base URL, model or api key** for any runtime | [Connection](../objects/runtime/connection.md). It is a `PUT /api/v1/connections/{name}` away; only the timeouts still need a restart |
| a **sidecar or LLM transport** (`RestClient` bean) | [Connection](../objects/runtime/connection.md). The four beans deliberately have **no** `.baseUrl(...)` — adding one back silently re-pins the URL to startup |
| a **`vidingest.<phase>.enabled` toggle** | [Connection](../objects/runtime/connection.md) if the phase has a connection row — KNOWLEDGE, DIARIZATION, FRAME_SAMPLE, OCR are editable at runtime; FUSE and CONTEXT are still environment-only. Also [PipelineRunPhase](../objects/run/pipeline-run-phase.md): a downstream phase must gate on the upstream's toggle *and* its skip flag |
| where **inference runs** | it is not a compose service. `VK_HOST_LLM_URL` points at a host process; the host must bind `0.0.0.0` or containers cannot reach it |

## What points INTO this tree from outside

These break silently — nothing in the tree references them, so no card names them.

**Found inside the repo:**

- `compose/services.yml` (lines 14-15) → `applications/vidingest/vidingest-server/Dockerfile` — build context is the repo root
- `compose/infra/infra.yml` → `compose/infra/paddleocr-server/`, `compose/infra/diarize-asr/` (build contexts)
- `compose/ports.env` → `VK_HOST_LLM_URL`, a process **outside** compose entirely. Nothing in the tree starts it, and nothing will tell you it is down except `POST /api/v1/connections/{name}/test`
- `applications/vidingest/vidingest-server/Dockerfile` → builds `applications/webapp` in a node stage with `--base-href=/vidingest/` into `resources/static/`
- `.env` (gitignored) → `VIDINGEST_*_ENABLED` phase toggles, `HUGGINGFACE_TOKEN`
- `.claude/settings.local.json` → disables the `shadcn` skill (React-only, cannot help here)

**Open question for the owner** — unanswerable from inside the tree, and the reason this section
exists: does anything outside the repo hardcode a path into it? Scheduled jobs, another repo's
config, an agent, a CI definition, an IDE run config. Record each answer on the card it lands on.
