---
type: object
cluster: derived
universe: live
status: verified
verified: 2026-08-28
commit: 0a40fa2
entity: applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/core/transcription/domain/Transcription.java
---

# Transcription (and TranscriptionSegment)

Two types, one card — they are written together by TRANSCRIBE and never edited apart. The parent
holds the full text and provider; the child holds the timed lines.

## Why this shape

- **`speaker_id` on a segment is nullable and `ON DELETE SET NULL`** because DIARIZE wipes and
  repopulates speakers for a video: re-running it alone recreates every speaker row under a new
  uuid, and the segment must survive that (`002-transcription.sql:47-49`).
- **`fullText` is stored alongside the segments**, not derived from them — the whisper sidecar
  returns both, and re-joining segments loses its punctuation decisions.
- Provider is a plain string, not an enum: whisper is the only caller today, so the constraint
  would be a guess.

## Shape

- `vidingest_transcriptions` — `Transcription.java:17`; `video` FK `CASCADE`, `language` (10),
  `fullText`, `provider` (50), `status` `TranscriptionStatus` — `:31`–`:44`
- `vidingest_transcription_segments` — `TranscriptionSegment.java:16`; `transcription` FK `CASCADE`,
  `startSeconds`/`endSeconds` `Float` (**not** `Double` — the fused segments use `Double`),
  `text`, `speakerId` — `:30`–`:48`
- Indexes: transcriptions on `video_id` and `status`; segments on `(transcription_id, start_seconds)`,
  `(start_seconds, end_seconds)`, `(speaker_id)` — `002-transcription.sql:37-38`, `:55-57`

## Connected to

- **owned-by:** [Video](../media/video.md) (`CASCADE`)
- **owns:** its segments (`CASCADE`)
- **joins:** [Speaker](speaker.md) by nullable `speaker_id`
- **looks-like-but-is-not:** [MultimodalSegment](multimodal-segment.md) — that is FUSE output over
  transcript **and** OCR, on its own index and its own `Double` timebase

## If you change this

- **Hits:** `TranscribePhase` and its wipe-then-repopulate, `DiarizePhase` (writes `speaker_id`
  back onto segments), `FusePhase` (reads segments), the whisper artifact endpoints
  (`/transcription/whisper.txt`, `/whisper.json`), `TranscriptionStatus` in `core/domain.ts`.
- **Does not hit:** [ContextChunk](context-chunk.md). CONTEXT chunks the fused segments, not the
  raw transcript.

## Surfaces

| Surface | Role |
|---|---|
| `TranscribePhase` | writes (wipe + repopulate, one transaction after the sidecar call) |
| `DiarizePhase` | updates `speaker_id` |
| console video detail | reads |

## See

- Source: `.../core/transcription/domain/`
