# VidIngest Console — Per-Phase Rerun

**Owner**: TradingLabs Platform
**Last reviewed**: 2026-05-22
**Status**: stable

**Applies to**:
- `vidingest-server` (REST + service)
- `vidingest-api` (DTO + path constants)

**Source of truth**:
- Code:
  - `applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/videos/controller/VideoPhaseController.java`
  - `applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/videos/service/VideoPhaseRunnerService.java`
  - `applications/vidingest/vidingest-api/src/main/java/com/tradinglabs/vidingest/api/videos/RunVideoPhaseResult.java`
  - `applications/vidingest/vidingest-api/src/main/java/com/tradinglabs/vidingest/api/paths/VidIngestApiPaths.java` (`VIDEO_PHASE_RUN`)
- Path constant: `VIDEO_PHASE_RUN = /api/v1/videos/{videoId}/phases/{phase}/run`

## Quickstart (for agents)

- Re-run a single phase on an existing video without re-downloading or repeating earlier phases.
- HTTP:
  ```bash
  curl -sX POST http://localhost:8051/vidingest/api/v1/videos/<videoId>/phases/<PHASE>/run
  ```
- Allowed `<PHASE>` values (case-insensitive, `-` or `_` separated):
  `TRANSCRIBE | DIARIZE | FRAME_SAMPLE | OCR | FUSE | KNOWLEDGE | CONTEXT`

## What this is

Per-phase rerun lets operators re-execute one pipeline phase against a video
that already passed PERSIST. Useful after:

- A sidecar / model upgrade (e.g. PaddleOCR weights bumped, qwen LLM swapped, embed model changed).
- A prompt change for KNOWLEDGE that needs to be applied to historical videos.
- A FUSE algorithm tweak that should propagate without re-downloading or re-transcribing.

Each phase service is already idempotent (wipe-then-repopulate semantics), so this
endpoint is a thin synchronous router over those existing services. There's no new pipeline
run created and no audit-run row — the call returns when the phase finishes (or fails).

## Phases supported

| Phase           | Service entrypoint                                    | Inputs (must exist in DB) | Output table(s) |
|-----------------|-------------------------------------------------------|---------------------------|-----------------|
| `TRANSCRIBE`    | `TranscriptionService.transcribe(Video)`              | on-disk video file        | `vidingest_transcriptions` + `vidingest_transcription_segments` |
| `DIARIZE`       | `DiarizationService.diarize(Video)`                   | transcription + audio     | `vidingest_speakers` + `transcription_segments.speaker_id` |
| `FRAME_SAMPLE`  | `FrameSamplingService.sampleFrames(Video)`            | on-disk video file        | `vidingest_video_frames` + `frames/NNNN.jpg` under the per-video folder |
| `OCR`           | `OcrService.ocrAllFrames(Video)`                      | sampled frames            | `vidingest_ocr_results` |
| `FUSE`          | `SegmentFusionService.fuse(Video)`                    | transcript / frames / OCR | `vidingest_multimodal_segments` |
| `KNOWLEDGE`     | `KnowledgeExtractionService.extractKnowledge(Video)`  | multimodal segments       | `vidingest_knowledge_units` + embeddings |
| `CONTEXT`       | `ContextChunkGenerationService.regenerateFor(Video)`  | multimodal segments → transcript fallback | `vidingest_context_chunks` |

Phases intentionally **not** exposed here: `METADATA`, `DOWNLOAD`, `PERSIST`. Those consume
the video URL (not the video row), so trigger a full pipeline run via
`POST /api/v1/pipelines` instead.

## Response shape

`RunVideoPhaseResult`:

```json
{
  "videoId": "29161251-874a-44b4-b875-1b9e230727ab",
  "phase": "OCR",
  "status": "OK",
  "message": null,
  "elapsedMs": 138469,
  "rowsAffected": 1972
}
```

| Field          | Meaning |
|----------------|---------|
| `status`       | `OK` on success, `ERROR` on failure |
| `message`      | failure detail when `status=ERROR`; `null` on success |
| `elapsedMs`    | wall-clock milliseconds spent running the phase |
| `rowsAffected` | phase-specific count (frames / OCR rows / segments / units / chunks). `null` for `TRANSCRIBE` + `DIARIZE` |

Note: the endpoint always returns HTTP 200 even on phase failure — failures live in the
JSON `status` field. Only validation problems (bad phase name, missing video) raise HTTP 400/404.

## Wire example

```bash
VID=29161251-874a-44b4-b875-1b9e230727ab
HOST=http://localhost:8051/vidingest

curl -sX POST "$HOST/api/v1/videos/$VID/phases/FUSE/run"
# {"videoId":"...","phase":"FUSE","status":"OK","elapsedMs":59,"rowsAffected":29}

curl -sX POST "$HOST/api/v1/videos/$VID/phases/KNOWLEDGE/run"
# {"videoId":"...","phase":"KNOWLEDGE","status":"OK","elapsedMs":851378,"rowsAffected":10}
```

## Architecture

Diagram:
- SVG render: `./diagrams/svg/vidingest-per-phase-rerun.svg`
- Mermaid source: `./diagrams/mermaid/vidingest-per-phase-rerun.mmd`

## Idempotency contract

Every phase service wipes its own prior rows for the video before re-populating:

| Phase         | Bulk-delete used                                        |
|---------------|---------------------------------------------------------|
| TRANSCRIBE    | `TranscriptionSegmentRepository.deleteByTranscriptionId` (then re-persists segments) |
| DIARIZE       | `SpeakerRepository.deleteByVideo_Id` + clears `speaker_id` on segments |
| FRAME_SAMPLE  | `VideoFrameRepository.deleteByVideo_Id` (cascades to ocr rows + removes JPGs) |
| OCR           | `OcrResultRepository.deleteByVideoId` |
| FUSE          | `MultimodalSegmentRepository.deleteByVideo_Id` |
| KNOWLEDGE     | `KnowledgeUnitRepository.deleteByVideo_Id` |
| CONTEXT       | `ContextChunkRepository.deleteByVideoId` |

All `deleteBy*` repos use explicit `@Modifying @Transactional @Query` so they run cleanly
from REST entrypoints that lack an ambient transaction. See "Gotchas" below.

## Performance (reference timings on Apple Silicon CPU, ~12 min video)

| Phase         | Elapsed | Notes |
|---------------|---------|-------|
| FUSE          | ~60 ms  | pure Java, in-memory aggregation |
| FRAME_SAMPLE  | ~8 s    | ffmpeg scene-change extraction (72 frames @ 10s interval) |
| CONTEXT       | ~35–50 s| 29 chunks × embed model round-trip |
| TRANSCRIBE    | ~95–130 s | Whisper `small` model |
| OCR           | ~140 s  | PaddleOCR 72 frames @ ~2 s/frame |
| DIARIZE       | ~430–450 s | pyannote.audio on CPU — bottleneck |
| KNOWLEDGE     | ~800–850 s | qwen2.5:14b-instruct, 2 batches @ ~5–8 min each |

## Gotchas

- **Synchronous.** Whisper / pyannote / qwen14b can take minutes. Use long client timeouts.
  The UI disables the matching button while the call is in-flight.
- **No new pipeline run.** Per-phase rerun does NOT create a `vidingest_pipeline_runs` row or
  audit events. If you need that, use the full pipeline retry endpoints
  (`POST /api/v1/pipelines/{runId}/retry`).
- **Phase prerequisites.** Calling a phase without its inputs is a no-op or a soft-fail.
  Example: `FUSE` on a video with no transcription returns 0 segments; `OCR` on a video
  with no frames returns 0 rows.
- **Transactional self-invocation.** `protected @Transactional` methods called via `this.`
  inside the same service get their proxy bypassed. All bulk-delete repo methods carry
  `@Modifying @Transactional @Query` so they self-bootstrap a transaction (fix landed
  May 2026 — see [Audit log](#repo-bulk-delete-audit) below).

## Repo bulk-delete audit

All `deleteBy*` methods used by phase services are now explicit `@Modifying @Transactional @Query`:

| Repository                                | Method                       | Notes |
|-------------------------------------------|------------------------------|-------|
| `OcrResultRepository`                     | `deleteByVideoId`            | Fixed first — original `TransactionRequiredException` symptom |
| `MultimodalSegmentRepository`             | `deleteByVideo_Id`           | |
| `VideoFrameRepository`                    | `deleteByVideo_Id`           | Cascades to `vidingest_ocr_results` via FK |
| `SpeakerRepository`                       | `deleteByVideo_Id`           | |
| `KnowledgeUnitRepository`                 | `deleteByVideo_Id`           | Also avoids pgvector array-delimiter SELECT path |
| `TranscriptionSegmentRepository`          | `deleteByTranscriptionId`    | |
| `ContextChunkRepository`                  | `deleteByVideoId`            | Caller (`regenerateFor`) is `@Transactional public` — kept derived |

## OpenAPI

Endpoint emitted under tag `video-phases` with operationId `runVideoPhase`. Schema:
`#/components/schemas/RunVideoPhaseResult`.

Spec source:
- Live springdoc: `http://localhost:8051/vidingest/v3/api-docs`

## Verification

Quick end-to-end sweep (single 12-min video, all phases):

```bash
VID=<video-uuid>
for phase in FUSE FRAME_SAMPLE OCR CONTEXT TRANSCRIBE DIARIZE KNOWLEDGE; do
  echo "=== $phase ==="
  curl -sX POST "http://localhost:8051/vidingest/api/v1/videos/$VID/phases/$phase/run" | jq
done
```

Expected: every entry returns `status:"OK"` with non-negative `elapsedMs`.

DB sanity:

```sql
SELECT
  (SELECT count(*) FROM vidingest_speakers WHERE video_id = '<vid>')               AS speakers,
  (SELECT count(*) FROM vidingest_video_frames WHERE video_id = '<vid>')           AS frames,
  (SELECT count(*) FROM vidingest_ocr_results o
    JOIN vidingest_video_frames f ON f.id = o.frame_id WHERE f.video_id = '<vid>') AS ocr,
  (SELECT count(*) FROM vidingest_multimodal_segments WHERE video_id = '<vid>')    AS multimodal,
  (SELECT count(*) FROM vidingest_knowledge_units WHERE video_id = '<vid>')        AS knowledge;
```

## Related

- [Knowledge Extraction](VidIngest%20Console%20-%20Knowledge%20Extraction.md)
- [Config and Runtime](VidIngest%20Console%20-%20Config%20and%20Runtime.md)
- [Data Model](VidIngest%20Console%20-%20Data%20Model.md)
