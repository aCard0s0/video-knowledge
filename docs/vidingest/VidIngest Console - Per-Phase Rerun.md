# VidIngest Console — Per-Phase Rerun

**Owner**: TradingLabs Platform
**Last reviewed**: 2026-08-25
**Status**: stable

**Applies to**:
- `vidingest-server` (REST + service)
- `vidingest-api` (DTO + path constants)

**Source of truth**:
- Code:
  - `applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/videos/controller/VideoPhaseController.java`
  - `applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/videos/service/VideoPhaseRunnerService.java`
  - `applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/pipeline/service/phase/PipelinePhaseRegistry.java` (`byPhase`)
  - `applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/pipeline/service/phase/PipelinePhaseContext.java` (`forRerun`)
  - `applications/vidingest/vidingest-api/src/main/java/com/tradinglabs/vidingest/api/videos/RunVideoPhaseResult.java`
  - `applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/commons/VidingestApiExceptionHandler.java`
  - `applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/commons/PhaseFailureException.java`
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

Returned on success only:

```json
{
  "videoId": "29161251-874a-44b4-b875-1b9e230727ab",
  "phase": "OCR",
  "elapsedMs": 138469,
  "rowsAffected": 1972
}
```

| Field          | Meaning |
|----------------|---------|
| `elapsedMs`    | wall-clock milliseconds spent running the phase |
| `rowsAffected` | phase-specific count (frames / OCR rows / segments / units / chunks). `null` for `TRANSCRIBE` + `DIARIZE` |

## Failures

A phase that fails propagates and is rendered as an RFC 7807 `ProblemDetail`, the same as
every other endpoint — there is no `status:"ERROR"` body and no 200-on-failure:

| Cause | Status | `title` |
|-------|--------|---------|
| Upstream tool or sidecar did not deliver (whisper, pyannote, ffmpeg, paddleocr, ollama) | `502` | `Upstream failure` |
| Unusable or non-rerunnable phase name | `400` | `Bad request` |
| Unknown `videoId` | `404` | `Not found` |
| Guard rail refused the work (e.g. chunk cap exceeded) | `409` | `Conflict` |
| Anything else | `500` | `Internal error` |

The 502 arm matches `PhaseFailureException`, the supertype of every phase's
`*FailureException`, so a phase added later is mapped correctly without touching the handler.

```json
{
  "type": "about:blank",
  "title": "Upstream failure",
  "status": 502,
  "detail": "paddleocr-server unreachable",
  "instance": "/api/v1/videos/29161251-874a-44b4-b875-1b9e230727ab/phases/OCR/run"
}
```

## Wire example

```bash
VID=29161251-874a-44b4-b875-1b9e230727ab
HOST=http://localhost:8051/vidingest

curl -sX POST "$HOST/api/v1/videos/$VID/phases/FUSE/run"
# {"videoId":"...","phase":"FUSE","elapsedMs":59,"rowsAffected":29}

curl -sX POST "$HOST/api/v1/videos/$VID/phases/KNOWLEDGE/run"
# {"videoId":"...","phase":"KNOWLEDGE","elapsedMs":851378,"rowsAffected":10}
```

## Architecture

`VideoPhaseRunnerService` does not know which service implements a phase. It resolves the
phase name to a `PipelineRunPhase`, looks the implementation up in `PipelinePhaseRegistry`,
and calls `PipelinePhase.execute(ctx)` with a `PipelinePhaseContext.forRerun(video)` — the
same objects the pipeline itself runs, so a rerun and an in-pipeline run cannot drift apart.
Adding a phase to the pipeline makes it reachable here with no change to this service; only
the `RERUNNABLE` set gates which phases the endpoint accepts.

`PipelinePhase.applies(ctx)` is deliberately **not** consulted — see "Gotchas".

Diagram:
- SVG render: `./diagrams/svg/vidingest-per-phase-rerun.svg`
- Mermaid source: `./diagrams/mermaid/vidingest-per-phase-rerun.mmd`

## Idempotency contract

Every phase service wipes its own prior rows for the video before re-populating. Whether the
wipe and the repopulate share a transaction is a per-phase decision, and the split ones are
split on purpose:

| Phase         | Bulk-delete used                                        | Wipe + repopulate |
|---------------|---------------------------------------------------------|-------------------|
| TRANSCRIBE    | `TranscriptionSegmentRepository.deleteByTranscriptionId` (then re-persists segments) | **atomic** |
| DIARIZE       | `SpeakerRepository.deleteByVideo_Id` + clears `speaker_id` on segments | **atomic** |
| FRAME_SAMPLE  | `VideoFrameRepository.deleteByVideo_Id` (cascades to ocr rows + removes JPGs) | **atomic** |
| OCR           | `OcrResultRepository.deleteByVideoId` | separate — see below |
| FUSE          | `MultimodalSegmentRepository.deleteByVideo_Id` | **atomic** |
| KNOWLEDGE     | `KnowledgeUnitRepository.deleteByVideo_Id` | separate — see below |
| CONTEXT       | `ContextChunkRepository.deleteByVideoId` | **atomic** |

The five atomic phases do all their slow work first — ffmpeg and the sidecar have already
returned, or fusion is pure Java — so `TransactionOperations` wraps just the two statements
and a failure can never leave a video with its old rows gone and its new ones unwritten.

`OCR` and `KNOWLEDGE` deliberately keep the wipe and the persist in separate transactions:
each wipes *before* a minutes-long loop (PaddleOCR per frame, the LLM per batch) and persists
after, and one transaction over that would hold a pooled connection for the whole loop. The
trade is stated in the code — empty rows plus a FAILED run beat stale rows interleaved with
fresh ones — and the phase fails loudly when nothing was produced rather than reporting a
successful zero.

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
- **Failures are HTTP failures.** Until Aug 2026 this endpoint answered `200` with
  `{"status":"ERROR","message":...}`, so clients that only check the status code read a failed
  rerun as a success. It now returns `502`/`500` with a ProblemDetail body, and
  `RunVideoPhaseResult` no longer carries `status` or `message`. Callers that branched on
  `status == "ERROR"` must branch on the HTTP status instead.
- **No new pipeline run.** Per-phase rerun does NOT create a `vidingest_pipeline_runs` row or
  audit events. If you need that, use the full pipeline retry endpoints
  (`POST /api/v1/pipelines/{runId}/retry`).
- **Deployment toggles are bypassed.** The phase runs even when its
  `vidingest.<phase>.enabled` property is `false`. This endpoint is the operator escape hatch
  ("re-OCR after a paddleocr-server upgrade"), so it calls `execute()` directly rather than
  going through `applies(ctx)`, which also mixes in per-run skip flags that mean nothing for
  a rerun. A phase whose sidecar is genuinely absent fails visibly with `status:"ERROR"`.
- **Video status.** `TRANSCRIBE` and `CONTEXT` move `vidingest_videos.status` while they run
  (`TRANSCRIBING` / `PROCESSING`) and leave finalisation to `PipelineService`, which a rerun
  does not go through. The runner therefore restores the status the video had before the
  call. On failure the phase's own `FAILED` status stands — a failed rerun no longer leaves
  the video looking `COMPLETED`.
- **Phase prerequisites.** Calling a phase without its inputs is a no-op or a soft-fail.
  Example: `FUSE` on a video with no transcription returns 0 segments; `OCR` on a video
  with no frames returns 0 rows. The exception is `OCR` when *no* frame was readable — every
  frame either failed the sidecar or has lost its JPG. That throws, because the prior rows
  are already wiped by then and returning 0 would destroy them silently.
- **Transactional self-invocation.** `@Transactional` on a `protected`, self-invoked method
  does nothing, twice over: `AnnotationTransactionAttributeSource` is `publicMethodsOnly`, so
  a non-public method never gets a transaction attribute, and `this.` calls bypass the proxy
  anyway. Six services carried such annotations. Putting `@Modifying @Transactional` on the
  bulk-delete repo methods (May 2026) made each *delete* atomic but left delete-then-insert
  split across two commits; Aug 2026 removed the inert annotations and gave the five phases
  above a real transaction via an injected `TransactionOperations` around the DB block only.
  When you need a transaction inside a method that also does process or HTTP work, inject
  `TransactionOperations` and wrap the narrow part — never annotate the driver.

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
| `ContextChunkRepository`                  | `deleteByVideoId`            | Aug 2026: was the only one without `@Transactional`, relying on `regenerateFor` to supply it — which had to go, because that boundary also spanned the blocking embeddings call |

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
  curl -sw ' HTTP %{http_code}\n' -X POST \
    "http://localhost:8051/vidingest/api/v1/videos/$VID/phases/$phase/run" | jq
done
```

Expected: every entry returns `HTTP 200` with non-negative `elapsedMs`. A `502` names the
sidecar that did not answer in `detail`.

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
