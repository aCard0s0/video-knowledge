---
type: object
cluster: derived
universe: live
status: verified
verified: 2026-08-28
commit: 0a40fa2
entity: applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/core/diarization/domain/Speaker.java
---

# Speaker

One diarized voice in one video. `label` (`"SPEAKER_00"`) is the natural key; `displayName` is what
a human renames it to.

## Why this shape

- **`UNIQUE (video_id, label)` is what makes the label safe to reference.** DIARIZE is
  wipe-then-repopulate, so a re-run recreates every row under a new uuid — a re-run reproduces the
  *label*, never the id. This is exactly why [MultimodalSegment](multimodal-segment.md) stores
  labels and not ids (`002-transcription.sql:15-20`).
- **No index on `video_id` alone** — it is the leftmost column of that unique constraint, so the
  planner prefers the narrower one and the extra index is dead weight that still reads as used in
  `pg_stat_user_indexes` (`002-transcription.sql:23`).
- **`embedding_voiceprint` is `vector(192)`**, the diarize-asr voiceprint width — unrelated to the
  1536 used for search embeddings.

## Shape

- `vidingest_speakers` — `:25`; `video` FK `CASCADE`, `label` (64) NOT NULL, `displayName` (255),
  `embeddingVoiceprint` `vector(192)` — `:39`–`:55`

## Connected to

- **owned-by:** [Video](../media/video.md) (`CASCADE`)
- **joins:** [TranscriptionSegment](transcription.md) by `speaker_id` (`SET NULL`),
  [MultimodalSegment](multimodal-segment.md) **by label, with no FK**
- **looks-like-but-is-not:** a person. A speaker is per-video; the same human across two videos is two rows.

## If you change this

- **Hits:** `DiarizePhase`, `SpeakerController` (`/videos/{id}/speakers`, `/speakers/{speakerId}`),
  `FusePhase` (reads labels), console video detail speaker pane.
- **Does not hit:** `multimodal_segments.speaker_labels`. There is no FK — renaming `displayName`
  changes nothing downstream, and changing `label` silently orphans the array.

## Surfaces

| Surface | Role |
|---|---|
| `DiarizePhase` | writes (wipe + repopulate per video) |
| `SpeakerController` | reads, renames |

## See

- Source: `.../core/diarization/domain/Speaker.java`
