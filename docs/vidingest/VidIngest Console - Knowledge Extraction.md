# VidIngest Console — Knowledge Extraction (M2–M8)

- **Owner**: TradingLabs Platform
- **Last reviewed**: 2026-05-13
- **Status**: stable, all phases default to disabled
- **Applies to**: `vidingest-server`, `vidingest-mcp`, `vidingest-cli`, `vidingest-api`,
  `vidingest-client`
- **Related**: [Data Model](VidIngest%20Console%20-%20Data%20Model.md),
  [Config and Runtime](VidIngest%20Console%20-%20Config%20and%20Runtime.md),
  [MCP with LM Studio](VidIngest%20Console%20-%20MCP%20with%20LM%20Studio.md),
  [CLI Commands](VidIngest%20Console%20-%20CLI%20Commands.md)

## What this is

A set of pipeline phases that turn a downloaded video into structured, agent-consumable
knowledge: speaker labels, OCR text from frames, fused per-window multimodal segments,
and LLM-extracted typed knowledge units (entities, topics, summaries, claims, questions).

All phases are **disabled by default** so existing pipelines keep their current behaviour
unchanged. Operators opt in per-deploy by flipping config flags and (where required)
standing up sidecars.

## Pipeline phases (M1–M8)

```
METADATA → DOWNLOAD → PERSIST → TRANSCRIBE → DIARIZE → FRAME_SAMPLE → OCR → FUSE → KNOWLEDGE → CONTEXT
```

| Phase | Milestone | Default | Inputs | Outputs | External deps |
|-------|-----------|---------|--------|---------|---------------|
| TRANSCRIBE   | (pre-M1) | on  | downloaded video file        | `vidingest_transcriptions` + `vidingest_transcription_segments`   | `whisper` sidecar |
| DIARIZE      | M2       | off | transcription + audio        | `vidingest_speakers`; `transcription_segments.speaker_id` populated | `diarize-asr` sidecar (HF token) |
| FRAME_SAMPLE | M3       | off | downloaded video file        | `vidingest_video_frames` + JPGs at `{baseName}/frames/NNNN.jpg`   | ffmpeg (already in image) |
| OCR          | M4       | off | sampled frames                | `vidingest_ocr_results`                                          | `paddleocr-server` sidecar |
| FUSE         | M5       | on  | transcript + speakers + OCR  | `vidingest_multimodal_segments` (one row per fusion window)      | — pure Java |
| KNOWLEDGE    | M6       | off | multimodal segments           | `vidingest_knowledge_units` + embeddings                         | Ollama (reuses embeddings daemon) |
| CONTEXT      | (M7-enhanced) | off (semantic-search-gated) | multimodal segments → transcript fallback | `vidingest_context_chunks` (richer content) | embeddings client |

Per-run skip flags (`skipDiarize`, `skipFrames`, `skipOcr`, `skipKnowledge`) are exposed
on every entry point (REST `CreatePipelineRunRequest`, MCP `createPipelineRuns`, CLI
`ingest`) and default to `true`. Skipping is also independent — operators can run any
subset (e.g. OCR-only without diarization).

## Architecture

```
                  ┌──────────────────────────┐
              ┌──▶│ whisper      (port 9000) │── TRANSCRIBE
              │   └──────────────────────────┘
              │   ┌──────────────────────────┐
              ├──▶│ diarize-asr  (port 9001) │── DIARIZE   (pyannote.audio 3.x)
              │   └──────────────────────────┘
vidingest ────┤   ┌──────────────────────────┐
  server      ├──▶│ paddleocr-server (8002)  │── OCR       (PaddleOCR)
              │   └──────────────────────────┘
              │   ┌──────────────────────────┐
              └──▶│ ollama      (port 11434) │── KNOWLEDGE + embeddings
                  └──────────────────────────┘
```

Frames live inside the per-video folder on disk
(`{videoPath}/{channelName}/{baseName}/frames/`) and cascade on delete (handled by
`VideoDeleteService`, which removes the whole `{baseName}/` folder). All Liquibase changesets
for the new tables live at `db/changelog/changesets/007-*.sql` through `012-*.sql`.

## Per-phase details

### DIARIZE (M2)
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

### FRAME_SAMPLE (M3)
- **Code**: `core/frames/` (Service + Phase + `ShowinfoParser`)
- **ffmpeg command**:
  ```
  ffmpeg -i {input} \
    -vf "select='gt(scene\,0.35)+isnan(prev_selected_t)+gte(t-prev_selected_t\,10.0)',showinfo" \
    -fps_mode passthrough -q:v 2 {framesDir}/%04d.jpg
  ```
- **Single pass** with combined scene-change OR fixed-interval select. Each frame is
  classified as `INTERVAL` or `SCENE_CHANGE` based on proximity to an interval boundary.
- **Idempotent**: re-runs wipe the `frames/` dir + `vidingest_video_frames` rows.

### OCR (M4)
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

### FUSE (M5)
- **Code**: `core/fusion/SegmentFusionService.java` (pure-Java)
- **Windowing**: default 30s windows with 5s overlap, configurable via
  `vidingest.fusion.window-seconds` / `window-overlap-seconds`. Empty windows are skipped
  so `segment_index` stays dense.
- **Per window**: concatenates overlapping transcript segments (preserving start-time
  order), collects distinct speaker UUIDs, deduplicates OCR lines by trimmed text.

### KNOWLEDGE (M6)
- **Code**: `core/knowledge/`
- **Prompt**: pure-function assembly in `KnowledgeExtractionPrompt.java`. System message
  pins a strict JSON output contract (single root key `"units"`, explicit ban-list of
  alternate root keys, illustrative example output). User message renders one block per
  segment with a global index. `PROMPT_VERSION` is written into each row's
  `metadata.prompt_version` for offline auditing — currently `2`.
- **Provider**: `vidingest.knowledge.provider=ollama` (default) — calls
  `POST {ollama}/api/chat` with **`format: <JSON schema>`** (Ollama 0.5+ structured outputs).
  The schema pins the response to `{units: [{type, title, content, ...}]}` so weaker open
  models can't drift to other root keys (we observed qwen2.5:7b emit `{transcript: ...}`
  before the schema was enforced). The provider abstraction (single Spring interface,
  `KnowledgeChatClient`) leaves room for OpenAI / Anthropic impls behind the same config
  flag.
- **Fallback parser**: `OllamaKnowledgeChatClient.locateUnitsArray` looks for `units` first,
  then alternates (`knowledge_units`, `items`, `data`, `results`), then any value array of
  objects with `type` + `content` fields. Defence-in-depth even after schema enforcement.
- **Recommended model**: `qwen2.5:14b-instruct` (`VIDINGEST_KNOWLEDGE_CHAT_MODEL`). Returns
  fewer but higher-salience units than the 7b variant — typical 12 min video → ~10–15
  CLAIM/SUMMARY-heavy units vs. 7b's noisy ~40 ENTITY/TOPIC dump.
- **Filtering**: drops drafts below `vidingest.knowledge.min-salience` (0.2) and types
  outside `vidingest.knowledge.types`. Caps total persisted to `max-units-per-video`.
- **Embedding**: each surviving unit's content is embedded via the existing
  `EmbeddingsClient` (1536-d, same shape as `context_chunks`). Embedding failure is soft —
  rows persist with `embedding=null` so they remain queryable by type/time.
- **Idempotent**: wipe-then-save on each run. The wipe goes through the explicit
  `@Modifying @Transactional @Query` on `KnowledgeUnitRepository.deleteByVideo_Id` —
  derived `deleteBy*` here would materialise the `vector(1536)` column via Hibernate's
  `FloatPrimitiveArrayJavaType` and trip the pgvector array-delimiter SELECT path.

### CONTEXT (M7-enhanced)
- **Code**: `search/service/embedding/ContextChunkGenerationService.java`
- **Change**: when `vidingest_multimodal_segments` rows exist, chunks are built from
  per-segment fused text (`transcript [VISUAL] ocr`) instead of raw transcript. Falls back
  to transcript-only when no multimodal segments are present — pre-M7 behaviour preserved
  byte-identically for videos ingested without fusion.

## REST API (M8)

All under `/vidingest/api/v1`. Existing endpoints unchanged; new endpoints:

| Method | Path | Description |
|--------|------|-------------|
| `GET`   | `/knowledge/search?query=&type=&limit=`            | Cross-video semantic search over `vidingest_knowledge_units` |
| `GET`   | `/videos/{videoId}/knowledge?type=`                | All knowledge units for a video, optional type filter |
| `POST`  | `/videos/{videoId}/knowledge/regenerate`           | Re-run M6 against current multimodal segments |
| `GET`   | `/videos/{videoId}/speakers`                       | Speakers + segment counts |
| `PATCH` | `/speakers/{speakerId}`                            | Rename a speaker (`{"displayName": "..."}` or blank to clear) |
| `GET`   | `/videos/{videoId}/multimodal-timeline?fromSeconds=&toSeconds=` | Fused timeline rows, optional time clip |
| `GET`   | `/videos/{videoId}/ocr`                            | OCR detections grouped by frame |
| `GET`   | `/frames/{frameId}/image`                          | Inline JPG bytes for a sampled frame (UI `<img src>`) |
| `POST`  | `/videos/{videoId}/phases/{phase}/run`             | Re-run one phase against an existing video. See [Per-Phase Rerun](VidIngest%20Console%20-%20Per-Phase%20Rerun.md) |

## MCP tools (M8)

Seven tools added on top of the original nine. See
[MCP with LM Studio](VidIngest%20Console%20-%20MCP%20with%20LM%20Studio.md) for the full
list and parameter reference.

- `searchKnowledge(query, type, limit)`
- `getKnowledgeUnits(videoId, type)`
- `regenerateKnowledge(videoId)`
- `getSpeakers(videoId)`
- `renameSpeaker(speakerId, displayName)`
- `getMultimodalTimeline(videoId, fromSeconds, toSeconds)`
- `getOcrResults(videoId)`

## CLI commands (M8)

```
search-knowledge --query "options gamma" --type ENTITY --limit 10
knowledge --video-id <UUID> [--type SUMMARY]
regenerate-knowledge --video-id <UUID>
speakers --video-id <UUID>
```

## Configuration

See [Config and Runtime](VidIngest%20Console%20-%20Config%20and%20Runtime.md) for the
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
VIDINGEST_KNOWLEDGE_BASE_URL=http://ollama:11434

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

### Enable end-to-end on Docker

```bash
# 1. Accept the pyannote EULA and set the token
export HUGGINGFACE_TOKEN=hf_...

# 2. Pull the chat model into Ollama
ollama pull qwen2.5:14b-instruct

# 3. Flip the master switches
export VIDINGEST_DIARIZATION_ENABLED=true
export VIDINGEST_FRAMES_ENABLED=true
export VIDINGEST_OCR_ENABLED=true
export VIDINGEST_KNOWLEDGE_ENABLED=true

# 4. Start the sidecars + server
docker compose -f compose/infra/infra.yml -f compose/services.yml up \
  whisper diarize-asr paddleocr-server ollama vidingest -d

# 5. Submit a pipeline run with all phases enabled
curl -sX POST http://localhost:8051/vidingest/api/v1/pipelines \
  -H "Content-Type: application/json" \
  -d '{
        "urls": ["https://www.youtube.com/watch?v=..."],
        "skipTranscription": false,
        "skipContext": false,
        "skipDiarize": false,
        "skipFrames": false,
        "skipOcr": false,
        "skipKnowledge": false
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

- **`Knowledge LLM returned HTTP 500`** — usually the chat model isn't pulled in Ollama.
  Run `ollama pull <model>` and retry.
- **`diarize-asr` returns 500 on first call** — `HUGGINGFACE_TOKEN` is unset, or the
  pyannote model EULA hasn't been accepted on the HuggingFace web UI.
- **Empty `vidingest_knowledge_units` after a run** — check that
  `vidingest_multimodal_segments` has rows (M5 must run before M6). The `FUSE` phase is
  on by default, but a video with no transcript / no OCR produces an empty fusion which
  in turn produces no knowledge units.
- **Knowledge extraction failed for every batch** — the LLM is unreachable. Knowledge
  partial-batch failures log but don't fail; all-batch failure does. Check
  `VIDINGEST_KNOWLEDGE_BASE_URL` and that the daemon is reachable from the
  `vidingest-server` container.

## Future work

- Vision captioning phase (deferred to M9): caption sampled frames with a vision LLM,
  fill the currently-empty `vision_summary` slot in the fusion fields.
- Cross-video knowledge graph: link entities across videos.
- Speaker re-identification across videos via voiceprint embeddings (the
  `Speaker.embedding_voiceprint` column is already populated when pyannote returns it).
- Per-channel enrichment profiles (NONE | BASIC | FULL) so the YouTube scheduler can
  apply different extraction levels per channel.

## Related pages

- [VidIngest Console](VidIngest%20Console.md) (overview)
- [Data Model](VidIngest%20Console%20-%20Data%20Model.md)
- [Config and Runtime](VidIngest%20Console%20-%20Config%20and%20Runtime.md)
- [MCP with LM Studio](VidIngest%20Console%20-%20MCP%20with%20LM%20Studio.md)
- [CLI Commands](VidIngest%20Console%20-%20CLI%20Commands.md)
- [YouTube Channels](VidIngest%20Console%20-%20YouTube%20Channels.md)
