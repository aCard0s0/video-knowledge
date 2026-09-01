---
type: reference
last_reviewed: 2026-08-29
---

# VidIngest — Knowledge Extraction

- **Owner**: TradingLabs Platform
- **Last reviewed**: 2026-09-01
- **Status**: stable, all phases default to disabled
- **Applies to**: `vidingest-server`, `vidingest-mcp`, `vidingest-cli`, `vidingest-api`,
  `vidingest-client`
- **Related**: [Data Model](VidIngest%20-%20Data%20Model.md),
  [Config and Runtime](VidIngest%20-%20Config%20and%20Runtime.md),
  [MCP with LM Studio](VidIngest%20-%20MCP%20with%20LM%20Studio.md),
  [CLI Commands](VidIngest%20-%20CLI%20Commands.md)

## What this is

A set of pipeline phases that turn a downloaded video into structured, agent-consumable
knowledge: speaker labels, OCR text from frames, fused per-window multimodal segments,
and LLM-extracted typed knowledge units (entities, topics, summaries, claims, questions).

All phases are **disabled by default** so existing pipelines keep their current behaviour
unchanged. Operators opt in per-deploy by flipping config flags and (where required)
standing up sidecars.

## Pipeline phases

```
METADATA → DOWNLOAD → PERSIST → TRANSCRIBE → DIARIZE → FRAME_SAMPLE → OCR → FUSE → KNOWLEDGE → CONTEXT
```

| Phase | Default | Inputs | Outputs | External deps |
|-------|---------|--------|---------|---------------|
| TRANSCRIBE   | on  | downloaded video file        | `vidingest_transcriptions` + `vidingest_transcription_segments`   | host ASR runtime (whisper-asr or OpenAI audio API) |
| DIARIZE      | off | transcription + audio        | `vidingest_speakers`; `transcription_segments.speaker_id` populated | `diarize-asr` sidecar (HF token) |
| FRAME_SAMPLE | off | downloaded video file        | `vidingest_video_frames` + JPGs at `{baseName}/frames/NNNN.jpg`   | ffmpeg (already in image) |
| OCR          | off | sampled frames                | `vidingest_ocr_results`                                          | `paddleocr-server` sidecar |
| FUSE         | on  | transcript + speakers + OCR  | `vidingest_multimodal_segments` (one row per fusion window)      | — pure Java |
| KNOWLEDGE    | off | multimodal segments           | `vidingest_knowledge_units` + embeddings                         | chat LLM on the host — OpenAI-compatible by default, ollama otherwise |
| CONTEXT      | off (semantic-search-gated) | multimodal segments → transcript fallback | `vidingest_context_chunks` (richer content) | embeddings client |

Per-run opt-outs travel as one `skipPhases` list naming the phases to skip, exposed on
every entry point (REST `CreatePipelineRunRequest`, MCP `createPipelineRuns`, CLI `ingest --skip-phases`). Any optional phase can be named — `TRANSCRIBE`, `DIARIZE`, `FRAME_SAMPLE`,
`OCR`, `FUSE`, `KNOWLEDGE`, `CONTEXT` — and the choices are independent, so operators can
run any subset (e.g. OCR-only without diarization). Naming a mandatory phase
(`METADATA`/`DOWNLOAD`/`PERSIST`) is a 400: those consume the source URL, not the video row,
so a run cannot start without them. The same predicate,
[`PipelineRunPhase.isOptional()`](../../applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/pipeline/domain/PipelineRunPhase.java),
also decides which phases the per-phase rerun endpoint accepts.

Request bodies are strict (`spring.jackson.deserialization.fail-on-unknown-properties`), so a
client still sending the six `skipTranscription`/`skipDiarize`/... booleans this replaced gets a
400 naming the property rather than a 202 and a run that quietly executed everything it asked
to skip.

## Architecture

```
                  ┌──────────────────────────┐
              ┌──▶│ diarize-asr  (port 9001) │── DIARIZE   (pyannote.audio 3.x)
              │   └──────────────────────────┘   compose sidecar, opt-in
vidingest ────┤   ┌──────────────────────────┐
  server      ├──▶│ paddleocr-server (8002)  │── OCR       (PaddleOCR)
              │   └──────────────────────────┘   compose sidecar, opt-in
              │   ┌──────────────────────────┐
              └──▶│ model runtime on the HOST│── TRANSCRIBE, KNOWLEDGE, embeddings
                  │ host.docker.internal:8000│
                  └──────────────────────────┘   not a container — see below
```

The three model connections point outside compose on purpose: a Linux container on Docker Desktop
cannot reach Apple Silicon's GPU, and the measurements under
[The default chat model does not fit CPU-only Docker](#the-default-chat-model-does-not-fit-cpu-only-docker)
are what that costs. All six connections (the two sidecars included) are repointable at runtime —
see [Connections API](VidIngest%20-%20Config%20and%20Runtime.md#connections-api-runtime-editable).

Frames live inside the per-video folder on disk
(`{videoPath}/{channelName}/{baseName}/frames/`) and cascade on delete (handled by
`VideoDeleteService`, which removes the whole `{baseName}/` folder). All Liquibase changesets
for the new tables live at `db/changelog/changesets/007-*.sql` through `012-*.sql`.

## Per-phase details

### DIARIZE
- **Code**: `core/diarization/` (Client + Service + Phase)
- **Wire contract**:
  ```
  POST {diarize-asr}/diarize?min_speakers=N&max_speakers=M
    Content-Type: multipart/form-data
    audio_file: <16kHz mono PCM WAV>

  200 → { "segments": [{"start","end","speaker"}], "speakers": [{"label","embedding"?}] }
  ```
- **Assignment**: each `TranscriptionSegment` is tagged with the speaker whose
  diarization window overlaps it by at least `vidingest.diarization.min-overlap-seconds`.
- **Sidecar**: built from `compose/infra/diarize-asr/` (FastAPI + pyannote.audio 3.x).
  Requires `HUGGINGFACE_TOKEN` — accept the pyannote/speaker-diarization-3.1 EULA on HF.

### FRAME_SAMPLE
- **Code**: `core/frames/` (Service + Phase + `ShowinfoParser`)
- **ffmpeg command**:
  ```
  ffmpeg -i {input} \
    -vf "select='gt(scene\,0.35)+isnan(prev_selected_t)+gte(t-prev_selected_t\,10.0)',showinfo" \
    -fps_mode passthrough -q:v 2 {framesDir}/%04d.jpg
  ```
- **Single pass** with combined scene-change OR fixed-interval select. Each frame is
  classified as `INTERVAL` or `SCENE_CHANGE` based on proximity to an interval boundary.
- **Idempotent**: re-runs replace the `frames/` dir + `vidingest_video_frames` rows. ffmpeg
  writes into a `frames.staging/` sibling that is promoted only once it exits cleanly, so a
  failure or a timeout leaves the previous frames and their rows exactly as they were. The row
  wipe and the re-insert share one transaction. If frames do go missing some other way (someone
  clears the volume), `OCR` fails loudly rather than wiping its own rows and reporting a
  successful zero — re-run `FRAME_SAMPLE` before `OCR` to recover.

### OCR
- **Code**: `core/ocr/` (Client + Service + Phase + Mapper)
- **Wire contract**:
  ```
  POST {paddleocr-server}/ocr?lang=en
    Content-Type: multipart/form-data
    image: <JPG bytes>

  200 → { "lines": [{"text","confidence","bbox":[[x,y],…],"language"}] }
  ```
- Lines below `vidingest.ocr.min-confidence` (0.5) are dropped; frames whose surviving
  line count is below `min-lines-per-frame` (1) are skipped entirely.
- **Sidecar**: built from `compose/infra/paddleocr-server/` (FastAPI + PaddleOCR).
  CPU-only by default; swap base image + replace `paddlepaddle` with `paddlepaddle-gpu`
  in `requirements.txt` for CUDA.

### FUSE
- **Code**: `core/fusion/SegmentFusionService.java` (pure-Java)
- **Windowing**: default 30s windows with 5s overlap, configurable via
  `vidingest.fusion.window-seconds` / `window-overlap-seconds`. Empty windows are skipped
  so `segment_index` stays dense.
- **Per window**: concatenates overlapping transcript segments (preserving start-time
  order), collects distinct speaker UUIDs, deduplicates OCR lines by trimmed text.

### KNOWLEDGE
- **Code**: `core/knowledge/`
- **Prompt**: pure-function assembly in `KnowledgeExtractionPrompt.java`. System message
  pins a strict JSON output contract (single root key `"units"`, explicit ban-list of
  alternate root keys), a glossary of the permitted types filtered to
  `vidingest.knowledge.types`, three ordered extraction priorities, and an exclusion list for
  promotional and interface noise. User message renders one block per segment with a global
  index. `PROMPT_VERSION` is written into each row's `metadata.prompt_version` for offline
  auditing — currently `3`.
- **v3 optimises for mechanism, not brevity** (Sep 2026). v2 asked for "fewer high-salience
  units", "deduplicate aggressively" and a 1–4 sentence body, which on instructional video threw
  away the thing worth keeping: a 3-minute trading tutorial stating a full entry/stop/target
  method extracted as one sentence, an ENTITY naming the video's *sponsor* and a CLAIM quoting its
  *ad copy*. Replaying that video's eight fused segments three times per prompt against
  `Qwen2.5-14B-Instruct-4bit`, scored against a hand-listed ground truth of the 19 rules the
  transcript states:

  | prompt | units | PROCEDUREs | rules recovered |
  |---|---|---|---|
  | v2 | 3.3 | 0.0 | 4.3 / 19 |
  | v3 | 6.0 | 3.0 | 14.7 / 19 |

  The sweep ran eleven variants and its most useful output is what turned out **not** to be true.
  Each of these is a change that looks like an improvement:
  - **No illustrative example output.** v2 had one showing two units; a v3 variant that kept it
    returned exactly one unit in 3 of 3 runs. A 14B anchors on the example's *cardinality*, and the
    JSON schema pins the shape far better than prose does. Re-add an example only if it shows as
    many units as a dense batch should produce.
  - **The nine "Preconditions/Confirmation/Entry/Stop/Target/…" slots stay an enumerated list.**
    Compressing them into a prose sentence cost 4.7 rules per run — the list works as a retrieval
    scaffold, so the model stops hunting for rule kinds it was not handed.
  - **Do not tell it off for filling those slots in.** The obvious follow-up — the model answers all
    nine, so forbid "None specified" — cost a further 2.7 rules. Those lines are deleted in code by
    `KnowledgeExtractionService.stripEmptySlotLines`, where it costs the model nothing.
  - **Glossary order matters, `vidingest.knowledge.types` order does not.** Moving `PROCEDURE` to
    the front of the allowed-types list changed nothing (11.3 vs 11.0). `PROCEDURE` leading the
    *glossary* is worth the gap between 14.7 and 11.0 — so the glossary order is pinned in
    `KnowledgeExtractionPrompt.GLOSSARY_ORDER` and an operator reordering `types` cannot degrade
    extraction by accident.
  - **The wording is a measured artifact.** A paraphrase preserving every structural element above
    — same sections, same slots, same priorities, reworded headers and sentences — measured 11.3
    against 14.7. The text in `KnowledgeExtractionPrompt` is byte-identical to the variant that
    scored 14.7. Edit it behind a fresh three-run comparison, not as a tidy-up.

  Two things v3 does not fix. No variant ever read the instrument name off the chart OCR (0/3
  everywhere), so a unit's ticker comes from the transcript or not at all. And it still opens a
  SUMMARY or TOPIC with "The video …" perhaps a third of the time despite being told twice not to —
  at this model size that instruction is a preference, not a constraint.

- **The harness resolves about ±3 rules — do not trust a smaller delta.** Ten runs of the shipped
  configuration across three separate batches scored 11–16 (sd 1.5), with batch means spanning
  12.7–14.7. Two numbers that were briefly recorded as findings sit inside that floor and have been
  demoted rather than deleted: "instructing the model not to write *None specified* cost 2.7 rules"
  and "a structure-preserving paraphrase cost 3.4". Both may be real; neither is established at
  n=3. What survives the floor is the v2→v3 gap (~9 rules, and 0 → 3 PROCEDURE units), the
  output-example collapse (1 unit in 3 of 3 runs — categorical, not a mean shift), and
  prose-folding the nine slots (~6 rules).
- **Video title and channel in the user message do not help — and this is how the floor bit.**
  Adding them looked decisive when the arms ran in blocks (15.3 against 12.7, and 4 individual
  facts where the treatment strictly dominated). Interleaving the arms **reversed the sign** (12.8
  against 13.5). Pooled over 7 runs each: 13.9 against 13.1 — nothing. Not applied. The user
  message still carries only index, time, transcript and on-screen text.
- **The higher-leverage fix was upstream, not in this phase.** `vidingest.transcription.prompt`
  ([Config and Runtime](VidIngest%20-%20Config%20and%20Runtime.md)) fixes the domain terms this
  phase then copies verbatim: `breaker structure` → `break of structure` in 3 of 3 runs, and zero
  ASR-error terms in the knowledge units afterwards. Worth checking before reaching for a bigger
  chat model.
- **Provider**: `vidingest.knowledge.provider` picks the *wire protocol*, not a vendor. Both
  impls sit behind `KnowledgeChatClient` and are selected per call by
  `KnowledgeChatClientRouter`, so `KnowledgeExtractionService` never learns which one it got:

  | value | client | endpoint | schema goes in | output cap |
  |---|---|---|---|---|
  | `ollama` (default) | `core/knowledge/client/ollama/OllamaKnowledgeChatClient` | `POST {base-url}/api/chat` | `format` (Ollama 0.5+ structured outputs) | `options.num_predict` |
  | `openai-compatible` | `core/knowledge/client/openai/OpenAiCompatibleKnowledgeChatClient` | `POST {base-url}/chat/completions` | `response_format.json_schema` | `max_tokens` |

  `openai-compatible` is the one to use for **LM Studio, `llama-server`, mlx-lm, vLLM** or a
  hosted API — they all serve that shape, and none of them serve Ollama's. Its `base-url` needs
  the API prefix the server exposes, usually `/v1`
  (`VIDINGEST_KNOWLEDGE_BASE_URL=http://localhost:1234/v1` for LM Studio), and
  `vidingest.knowledge.api-key` is sent as `Authorization: Bearer` when set.

  Either way the schema pins the response to `{units: [{type, title, content, ...}]}` so weaker
  open models can't drift to other root keys (we observed qwen2.5:7b emit `{transcript: ...}`
  before the schema was enforced). The schema itself and the parsing of what comes back live in
  `core/knowledge/client/KnowledgeUnitJson` and are shared by both clients — a fallback added
  for one runtime cannot silently not exist for the other.

  An unrecognised `provider` value **fails the KNOWLEDGE phase**, not the context at startup —
  the router resolves per call because `PUT /api/v1/connections/KNOWLEDGE` can change the value
  while the app runs, and a bean chosen at startup cannot follow a property that moves afterwards
  (PR #46). The connections API validates the value against the router's own supported set on the
  way in, so a typo is still caught before any run. There is no `Disabled` floor here as there is
  for embeddings, because `vidingest.knowledge.enabled=false` is already the off switch.
- **Fallback parser**: `KnowledgeUnitJson.locateUnitsArray` looks for `units` first,
  then alternates (`knowledge_units`, `items`, `data`, `results`), then any value array of
  objects with `type` + `content` fields. Defence-in-depth even after schema enforcement.
- **Recommended model**: `qwen2.5:14b-instruct` (`VIDINGEST_KNOWLEDGE_CHAT_MODEL`). Returns
  fewer but higher-salience units than the 7b variant — typical 12 min video → ~10–15
  CLAIM/SUMMARY-heavy units vs. 7b's noisy ~40 ENTITY/TOPIC dump.
- **Do not switch to a reasoning model on oMLX.** oMLX does not enforce `response_format`, so
  `Qwen3.5-9B-MLX-4bit` emits its chain-of-thought as `message.content`, hits
  `finish_reason: length` and returns no JSON at all — the phase fails rather than improves. The
  connections API will accept the model name (it validates the *provider*, not the model's
  behaviour), so this is not caught before a run.
- **Filtering**: drops drafts below `vidingest.knowledge.min-salience` (0.2) and types
  outside `vidingest.knowledge.types`. Caps total persisted to `max-units-per-video`.
- **Embedding**: each surviving unit's content is embedded via the existing
  `EmbeddingsClient` (1536-d, same shape as `context_chunks`). Embedding failure is soft —
  rows persist with `embedding=null` so they remain queryable by type/time.
- **Idempotent**: wipe-then-save on each run, both in one short transaction *after* the LLM
  batch loop. The loop itself still holds no pooled connection — one transaction across it would
  pin a connection for every chat round-trip — but the wipe no longer commits ahead of it, so
  nothing that fails mid-loop can leave the video with its units destroyed and nothing to replace
  them. The wipe goes through the explicit `@Modifying @Transactional @Query` on
  `KnowledgeUnitRepository.deleteByVideo_Id` — derived `deleteBy*` here would materialise the
  `vector(1536)` column via Hibernate's `FloatPrimitiveArrayJavaType` and trip the pgvector
  array-delimiter SELECT path.
- **Any failed batch fails the phase.** Partial coverage is not a valid replacement for a
  complete extraction: salvaging the batches that worked used to swap a video's 300 units for the
  dozen a single surviving batch produced and report success. The replace is atomic and runs only
  once every batch has succeeded, so a flaky LLM runtime leaves the existing rows untouched and the run
  is marked FAILED. A run where every batch succeeded but nothing cleared the salience floor
  *does* clear the table — that is a real answer, not a failure.

### CONTEXT
- **Code**: `search/service/embedding/ContextChunkGenerationService.java`
- **Change**: when `vidingest_multimodal_segments` rows exist, chunks are built from
  per-segment fused text (`transcript [VISUAL] ocr`) instead of raw transcript. Falls back
  to transcript-only when no multimodal segments are present
  byte-identically for videos ingested without fusion.

## REST API

All under `/vidingest/api/v1`. Existing endpoints unchanged; new endpoints:

| Method | Path | Description |
|--------|------|-------------|
| `GET`   | `/knowledge/search?query=&type=&limit=`            | Cross-video semantic search over `vidingest_knowledge_units` |
| `GET`   | `/videos/{videoId}/knowledge?type=`                | All knowledge units for a video, optional type filter |
| `POST`  | `/videos/{videoId}/knowledge/regenerate`           | Re-run `KnowledgePhase` against current multimodal segments |
| `GET`   | `/videos/{videoId}/speakers`                       | Speakers + segment counts |
| `PATCH` | `/speakers/{speakerId}`                            | Rename a speaker (`{"displayName": "..."}` or blank to clear) |
| `GET`   | `/videos/{videoId}/multimodal-timeline?fromSeconds=&toSeconds=` | Fused timeline rows, optional time clip |
| `GET`   | `/videos/{videoId}/ocr`                            | OCR detections grouped by frame |
| `GET`   | `/videos/{videoId}/ocr/frames`                     | The same, paged by frame |
| `GET`   | `/videos/{videoId}/multimodal-timeline/page`       | Paged variant of the timeline above |
| `GET`   | `/frames/{frameId}/image`                          | Inline JPG bytes for a sampled frame (UI `<img src>`) |
| `POST`  | `/videos/{videoId}/phases/{phase}/run`             | Re-run one phase against an existing video. See [Per-Phase Rerun](VidIngest%20-%20Per-Phase%20Rerun.md) |

## MCP tools

Seven tools added on top of the original nine. See
[MCP with LM Studio](VidIngest%20-%20MCP%20with%20LM%20Studio.md) for the full
list and parameter reference.

- `searchKnowledge(query, type, limit)`
- `getKnowledgeUnits(videoId, type)`
- `regenerateKnowledge(videoId)`
- `getSpeakers(videoId)`
- `renameSpeaker(speakerId, displayName)`
- `getMultimodalTimeline(videoId, fromSeconds, toSeconds)`
- `getOcrResults(videoId)`

## CLI commands

```
search-knowledge --query "options gamma" --type ENTITY --limit 10
knowledge --video-id <UUID> [--type SUMMARY]
regenerate-knowledge --video-id <UUID>
speakers --video-id <UUID>
```

## Configuration

See [Config and Runtime](VidIngest%20-%20Config%20and%20Runtime.md) for the
full property reference. Key environment variables:

```bash
# Master switches (all default false except FUSION which is true)
VIDINGEST_DIARIZATION_ENABLED=true
VIDINGEST_FRAMES_ENABLED=true
VIDINGEST_OCR_ENABLED=true
VIDINGEST_FUSION_ENABLED=true
VIDINGEST_KNOWLEDGE_ENABLED=true

# Sidecar URLs
VIDINGEST_DIARIZATION_BASE_URL=http://diarize-asr:9001
VIDINGEST_OCR_BASE_URL=http://paddleocr-server:8002
VIDINGEST_KNOWLEDGE_BASE_URL=http://host.docker.internal:8000/v1

# Diarization
HUGGINGFACE_TOKEN=<your-token>   # required by the diarize-asr sidecar
VIDINGEST_DIARIZATION_MAX_SPEAKERS=8
VIDINGEST_DIARIZATION_MIN_OVERLAP_SECONDS=0.25

# Frames
VIDINGEST_FRAMES_INTERVAL_SECONDS=10
VIDINGEST_FRAMES_SCENE_CHANGE_THRESHOLD=0.35
VIDINGEST_FRAMES_MAX_PER_VIDEO=600

# OCR
VIDINGEST_OCR_LANGUAGES=en
VIDINGEST_OCR_MIN_CONFIDENCE=0.5

# Knowledge
VIDINGEST_KNOWLEDGE_CHAT_MODEL=qwen2.5:14b-instruct
VIDINGEST_KNOWLEDGE_TEMPERATURE=0.2
VIDINGEST_KNOWLEDGE_MIN_SALIENCE=0.2
```

## Operational runbook

### Getting a HUGGINGFACE_TOKEN

`pyannote/speaker-diarization-3.1` is gated, so the token is not optional and a token
without the accepted EULA fails the same way a missing one does. Three steps, all on
huggingface.co, all free:

1. **Account** — sign up at <https://huggingface.co/join> (or sign in).
2. **Accept two EULAs.** The pipeline loads a second gated model internally, and missing
   either one 401s the download:
   - <https://huggingface.co/pyannote/speaker-diarization-3.1>
   - <https://huggingface.co/pyannote/segmentation-3.0>

   Open each, fill in the short "gated model" form on the model card, submit. Access is
   granted immediately — the card stops showing the form.
3. **Create a token** at <https://huggingface.co/settings/tokens> → *Create new token* →
   type **Read**. Copy it (`hf_…`); the value is shown once.

Put it in the gitignored `.env` at the repo root rather than exporting it, so every
`tradey.sh` invocation picks it up:

```bash
HUGGINGFACE_TOKEN=hf_...
```

Verify from inside the sidecar once it is up — this is the same call the DIARIZE phase makes,
so a 200 here means the phase will work:

```bash
docker exec video-knowledge-diarize-asr-1 \
  python -c "import os;from huggingface_hub import HfApi;print(HfApi().model_info('pyannote/speaker-diarization-3.1', token=os.environ['HUGGINGFACE_TOKEN']).id)"
```

`401` means the token is wrong; `403` means an EULA is still unaccepted.

### Enable end-to-end on Docker

`.env` at the repo root is read automatically by `docker compose`, and so by `tradey.sh`.
It is gitignored, which is what makes it the right home for the token.

```bash
# 1. .env — token from the section above, plus the master switches
cat >> .env <<'EOF'
HUGGINGFACE_TOKEN=hf_...
VIDINGEST_DIARIZATION_ENABLED=true
VIDINGEST_FRAMES_ENABLED=true
VIDINGEST_OCR_ENABLED=true
VIDINGEST_KNOWLEDGE_ENABLED=true
EOF

# 2. Pull the models. The embed model is required by CONTEXT whenever semantic search is
#    on (the default) — the schema pins VECTOR(1536), so this model, not a 768-dim one.
# The models live on the host runtime now — pull them with whatever it provides
# (oMLX's admin UI, `ollama pull`, `mlx_lm.convert`, ...) and check what it serves:
curl -s http://localhost:8000/v1/models | jq '.data[].id'

# 3. Build and start the sidecars, then restart the server onto the new .env
./scripts/tradey.sh start sidecars --build
./scripts/tradey.sh start vidingest

# 4. Both sidecars must read (healthy) before a run — an enabled phase whose sidecar is
#    unreachable fails the phase, and with it the whole run
./scripts/tradey.sh status

# 5. Submit a pipeline run with all phases enabled
curl -sX POST http://localhost:8051/vidingest/api/v1/pipelines \
  -H "Content-Type: application/json" \
  -d '{
        "urls": ["https://www.youtube.com/watch?v=..."],
        "skipPhases": []
      }'
```

### Sanity-check the results

```sql
-- Speakers found in the latest video
SELECT s.label, s.display_name, count(t.id) AS segments
FROM vidingest_speakers s
LEFT JOIN vidingest_transcription_segments t ON t.speaker_id = s.id
WHERE s.video_id = '<videoId>'
GROUP BY s.id;

-- Frames sampled
SELECT sampling_reason, count(*) FROM vidingest_video_frames
WHERE video_id = '<videoId>' GROUP BY sampling_reason;

-- OCR row count + average confidence
SELECT count(*), avg(confidence) FROM vidingest_ocr_results r
JOIN vidingest_video_frames f ON f.id = r.frame_id
WHERE f.video_id = '<videoId>';

-- Knowledge units by type
SELECT type, count(*), avg((metadata->>'salience')::float) AS avg_salience
FROM vidingest_knowledge_units
WHERE video_id = '<videoId>' GROUP BY type;
```

### Troubleshooting

- **`Knowledge LLM returned HTTP 500`** — usually the chat model isn't pulled/loaded on the
  runtime. On `openai-compatible`, a `404` here usually means the `/v1` suffix is missing
  from `base-url` (the client appends `/chat/completions` to whatever it is given).
  Run `ollama pull <model>` and retry.
- **`diarize-asr` returns 500 on first call** — `HUGGINGFACE_TOKEN` is unset, or the
  pyannote model EULA hasn't been accepted on the HuggingFace web UI.
- **Empty `vidingest_knowledge_units` after a run** — check that
  `vidingest_multimodal_segments` has rows (FUSE must run before KNOWLEDGE). The `FUSE` phase is
  on by default, but a video with no transcript / no OCR produces an empty fusion which
  in turn produces no knowledge units.
- **Knowledge extraction failed for N of M batches** — the LLM is unreachable or flaky.
  Prior knowledge units are left untouched. Knowledge
  partial-batch failures log but don't fail; all-batch failure does. Check
  `VIDINGEST_KNOWLEDGE_BASE_URL` and that the daemon is reachable from the
  `vidingest-server` container.
- **`Error while extracting response for type [byte[]] and content type
  [application/octet-stream]`, with `elapsedMs` at exactly 600000** — that is not a codec
  problem, it is `vidingest.knowledge.read-timeout` (default `10m`) expiring mid-read. The
  message is the socket timeout surfacing as a decode failure.

### The default chat model does not fit CPU-only Docker

**Resolved Aug 2026 by removing the `llm` container.** Kept because it is the measurement that
justified the move, and because anyone tempted to put the runtime back in compose should read it
first.

Measured on this repo's old compose stack (Docker Desktop, Apple Silicon, 10 CPUs, no GPU):

| | `qwen2.5:14b-instruct` | `qwen2.5:0.5b-instruct` |
|---|---|---|
| model load | **384 s** | 51 s |
| one batch, 360 input chars | timed out at 600 s | 415 s |
| result | phase FAILED | 1 unit, phase OK |

`ollama ps` reported `size_vram 0.0 GB` — **Docker Desktop gives Linux containers no access to
Apple Silicon's Metal GPU**, so all inference was CPU-only. The `qwen2.5:14b-instruct` default
could not complete a batch inside the 10-minute read timeout, and no amount of raising that
timeout makes a 14b model on CPU practical for ingestion. `OLLAMA_KEEP_ALIVE=30s` compounded it:
the chat and embed models evicted each other, so a run paid a fresh load for each.

The fix was option 1 below, applied to the default rather than left as advice:

1. **Run the model runtime on the host** and point the connections at it. This is now what compose
   does — `VK_HOST_LLM_URL`, default `http://host.docker.internal:8000/v1`. The host process must
   bind `0.0.0.0`; a container reaches it on the bridge address, so a loopback-only listener
   refuses the connection.
2. **Use a small chat model** if the host is modest — `qwen2.5:3b-instruct` is a reasonable floor
   for structured extraction. `0.5b` proves the wiring but its output is poor: on the sample above
   it emitted `entity_type: ORGANIZATION` for the entity `elephant`.
3. **Leave KNOWLEDGE off** for local work, as it is by default.

## Future work

- Vision captioning phase (deferred to M9): caption sampled frames with a vision LLM,
  fill the currently-empty `vision_summary` slot in the fusion fields.
- Cross-video knowledge graph: link entities across videos.
- Speaker re-identification across videos via voiceprint embeddings (the
  `Speaker.embedding_voiceprint` column is already populated when pyannote returns it).
- Per-channel enrichment profiles (NONE | BASIC | FULL) so the YouTube scheduler can
  apply different extraction levels per channel.

## Related pages

- [VidIngest](VidIngest.md) (overview)
- [Data Model](VidIngest%20-%20Data%20Model.md)
- [Config and Runtime](VidIngest%20-%20Config%20and%20Runtime.md)
- [MCP with LM Studio](VidIngest%20-%20MCP%20with%20LM%20Studio.md)
- [CLI Commands](VidIngest%20-%20CLI%20Commands.md)
- [YouTube Channels](VidIngest%20-%20YouTube%20Channels.md)
