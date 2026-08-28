---
type: object
cluster: derived
universe: live
status: verified
verified: 2026-08-28
commit: 0a40fa2
entity: applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/core/frames/domain/VideoFrame.java
---

# VideoFrame

One sampled still, on disk at `filePath`, indexed by position in the sample and by timestamp.
FRAME_SAMPLE writes it; OCR reads it.

## Why this shape

- **`samplingReason` records *why* the frame was taken** (`INTERVAL` or `SCENE_CHANGE`), because the
  two produce very different frame densities and an operator looking at 400 frames needs to know
  which mode ran (`SamplingReason.java:16-17`).
- **`UNIQUE (video_id, frame_index)`** makes re-sampling idempotent; no index on `video_id` alone,
  it is the leftmost column of both that constraint and the time composite (`003-frames-ocr.sql:18`, `:22`).
- **ffmpeg is never called directly.** Every invocation goes through `FfmpegRunner`, which drains
  on a separate thread, passes `-nostdin` and closes the child's stdin. Draining on the waiting
  thread makes the timeout unreachable for exactly the hung process it exists to kill.

## Shape

- `vidingest_video_frames` — `:30`; `video` FK `CASCADE`, `frameIndex`, `timestampSeconds` `Double`,
  `filePath`, `samplingReason` (32), `width`, `height` — `:44`–`:63`
- Index `(video_id, timestamp_seconds)` — `003-frames-ocr.sql:23`

## Connected to

- **owned-by:** [Video](../media/video.md) (`CASCADE`)
- **owns:** [OcrResult](ocr-result.md) (`CASCADE`)
- **looks-like-but-is-not:** a thumbnail. These are analysis inputs; the console serves them through
  `/frames/{frameId}/image` for inspection, not as artwork.

## If you change this

- **Hits:** `FrameSamplingService` (and `runFfmpeg`, which the tests override to fake ffmpeg),
  `OcrPhase` (its input), `FrameArtifactsController`, files under the storage root.
- **Does not hit:** [MultimodalSegment](multimodal-segment.md) directly. FUSE reads OCR *text*, not frames.

## Surfaces

| Surface | Role |
|---|---|
| `FrameSamplePhase` | writes rows + files |
| `OcrPhase` | reads |
| console video detail (frames pane) | reads via `/frames/{frameId}/image` |

## See

- Source: `.../core/frames/domain/VideoFrame.java`, `.../core/frames/`
