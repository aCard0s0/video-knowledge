---
type: object
cluster: derived
universe: live
status: verified
verified: 2026-08-28
commit: 0a40fa2
entity: applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/core/ocr/domain/OcrResult.java
---

# OcrResult

Text read off one [VideoFrame](video-frame.md) by the paddleocr sidecar, with its bounding box and
confidence.

## Why this shape

- **OCR's failure policy is "skip the frame, fail only if none was readable."** One unreadable
  frame is one frame of coverage; throwing on it would lose the other 399. This is deliberately the
  *opposite* of [KnowledgeUnit](knowledge-unit.md), where one failed batch is ~40 segments and
  salvaging the rest would silently narrow the extraction.
- **The transaction covers only the wipe and the repopulate, taken after the loop.** OCR's loop sits
  between the read and the write, so the loop runs connection-free — the pool is 10. Committing the
  wipe *before* the loop was the older answer and made every mid-loop failure a silent data loss.
- **`bbox` is `jsonb` holding `List<List<Double>>`** — a polygon, not a rectangle; paddleocr returns
  four corner points (`:55-57`).

## Shape

- `vidingest_ocr_results` — `:28`; `frame` FK `CASCADE`, `text`, `confidence` `Float`,
  `bbox` `jsonb`, `language` (10) — `:42`–`:60`
- Index `frame_id` — `003-frames-ocr.sql:36`

## Connected to

- **owned-by:** [VideoFrame](video-frame.md) (`CASCADE`) — **not** `Video` directly, so it cascades two levels
- **joins:** [MultimodalSegment](multimodal-segment.md) — FUSE copies the text into `ocr_text`
- **looks-like-but-is-not:** a transcript. Different modality, different timebase (frame timestamp, not span).

## If you change this

- **Hits:** `OcrPhase` and its transaction boundary, the paddleocr sidecar contract, `FusePhase`,
  `/videos/{id}/ocr` and `/ocr/frames`.
- **Does not hit:** [Video](../media/video.md) status. A video with zero readable frames still
  completes; only "no frame readable at all" throws.

## Surfaces

| Surface | Role |
|---|---|
| `OcrPhase` | writes (wipe + repopulate, `vidingest.ocr.enabled`, default false) |
| paddleocr sidecar (`:8002`) | produces; started by `./vk start sidecars` |

## See

- Source: `.../core/ocr/`
