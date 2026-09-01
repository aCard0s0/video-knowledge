---
type: object
cluster: derived
universe: live
status: verified
verified: 2026-08-28
commit: 0a40fa2
entity: applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/core/fusion/domain/MultimodalSegment.java
---

# MultimodalSegment

FUSE output: one time span carrying transcript text, OCR text and the speakers heard in it. This is
what KNOWLEDGE and CONTEXT actually read — not the raw transcript.

## Why this shape

- **`speaker_labels` is `text[]` of *labels*, never ids.** The old `speaker_ids UUID[]` had no
  foreign key, and DIARIZE is wipe-then-repopulate: re-running it alone recreated every speaker
  under a new uuid, so the array served ids for deleted rows. `UNIQUE (video_id, label)` on
  `vidingest_speakers` already makes the label a natural key a re-run reproduces, so the reference
  cannot dangle (`004-knowledge.sql:17-22`).
- **`startSeconds`/`endSeconds` are `Double` here and `Float` on transcription segments.** Not a
  bug, but do not assume one timebase across the two tables.
- **`ocrText` is filtered, not raw.** `SegmentFusionService` drops two classes of OCR line before
  the row is written, because a screen recording picks up the browser and the price axis along with
  the content — on the reference video OCR was **54% of what the KNOWLEDGE phase read** (5852 chars
  against 4938 of transcript), and under prompt v2 the model made the repeated sponsor watermark its
  ENTITY. `carriesMeaning` drops a line with no letter in it (a chart price axis is a number with
  nothing saying what it refers to; 27% of raw OCR chars), and `chromeOcrLines` drops a line present
  in ≥ `vidingest.fusion.ocr-chrome-window-ratio` of the video's windows, since chrome is static and
  content is not. Together: 54% → 43%, and the whole KNOWLEDGE prompt 17% smaller.
  **Rule recovery was unchanged** (13.8 against 14.8 of 19, inside the eval's noise floor), so the
  filters are a token saving rather than a quality gain — the theory that a smaller prompt would
  extract more was tested and did not hold. **Confidence and bbox position were measured and
  rejected** as further filters — see `SegmentFusionService.carriesMeaning`. Anything asserting on
  `ocrText` needs to know it has been through both.
- **No index on `video_id` alone** — leftmost column of both the unique constraint and the time
  composite (`004-knowledge.sql:28`).

## Shape

- `vidingest_multimodal_segments` — `:30`; `video` FK `CASCADE`, `segmentIndex`,
  `startSeconds`/`endSeconds` `Double`, `transcriptText`, `ocrText`,
  `speakerLabels` `text[]` — `:44`–`:75`
- `UNIQUE (video_id, segment_index)`; index `(video_id, start_seconds)` — `004-knowledge.sql:24`, `:29`

## Connected to

- **owned-by:** [Video](../media/video.md) (`CASCADE`)
- **joins:** [Speaker](speaker.md) **by label, no FK**; consumed by [KnowledgeUnit](knowledge-unit.md) and [ContextChunk](context-chunk.md)
- **looks-like-but-is-not:** [TranscriptionSegment](transcription.md) — different index, different timebase, different owner

## If you change this

- **Hits:** `FusePhase`, `KnowledgePhase` (batches ~40 of these), `ContextPhase`,
  `VideoMultimodalArtifactsController` (`/multimodal-timeline`, `/page`), console timeline pane.
- **Does not hit:** [Speaker](speaker.md) rows. The label array is a copy — renaming a speaker's
  `label` orphans it silently, and no constraint will tell you.

## Surfaces

| Surface | Role |
|---|---|
| `FusePhase` | writes (wipe + repopulate) |
| `KnowledgePhase`, `ContextPhase` | read |

## See

- Source: `.../core/fusion/domain/MultimodalSegment.java`
