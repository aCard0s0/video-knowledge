---
type: object
cluster: media
universe: live
status: verified
verified: 2026-08-28
commit: 0a40fa2
entity: applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/videos/domain/Video.java
---

# Video

The persisted video row — identity, metadata, and the path to the downloaded file. Created at
PERSIST. **Every optional phase consumes this row**, which is why those phases can be re-run alone
and METADATA/DOWNLOAD/PERSIST cannot.

## Why this shape

- **Identity is `UNIQUE (source, source_video_id)`, not the URL.** Two sites can collide on a bare
  id, so the pair is the key (`001-pipeline.sql:52`, `:66`).
- **`pipeline_run_id` is `ON DELETE SET NULL` and means "the run that *last* touched this"** —
  not ownership. Re-ingesting the same video re-points it (`001-pipeline.sql:46-49`).
- **`metadata` is `jsonb` with no GIN index, deliberately.** Nothing queries it by content — no
  `@>`, no `->>`. The index that used to exist was 1656 kB against three rows. Add one back only
  alongside the query that needs it (`001-pipeline.sql:69-70`).
- **Deleting is row-first, artifacts-second, and the artifact delete is post-commit.** A recursive
  directory delete cannot roll back; orphaned bytes are recoverable, an unplayable row is not
  (`VideoDeleteService.java:33-44`, `:92-96`). The containment check that refuses paths outside the
  storage root aborts the row delete too — that one is a guard, not cleanup (`:60-72`).

## Shape

- `vidingest_videos` — `Video.java:32`
- `source` + `sourceVideoId` (the unique pair), `filePath`, `status` `VideoStatus` (8 constants) — `:57`, `:60`, `:81`, `:89`
- `title`, `description`, `channelName`, `durationSeconds`, `publishedAt`, `downloadedAt` — `:63`–`:78`
- `metadata` `jsonb` — `:85`
- Indexes: `channel_name`, `status`, `pipeline_run_id` — `001-pipeline.sql:72-74`

## Connected to

- **owns:** [Transcription](../derived/transcription.md), [Speaker](../derived/speaker.md), [VideoFrame](../derived/video-frame.md), [MultimodalSegment](../derived/multimodal-segment.md), [KnowledgeUnit](../derived/knowledge-unit.md), [ContextChunk](../derived/context-chunk.md) — all `ON DELETE CASCADE`
- **owned-by:** nothing. `pipeline_run_id` is a back-reference, not a parent
- **joins:** [PipelineRunItem](../run/pipeline-run-item.md) by `video_id`
- **looks-like-but-is-not:** [YoutubeChannelVideo](youtube-channel-video.md) — that is a *discovered candidate*, not an ingested video, and there is no FK between them

## If you change this

- **Hits:** every derived table via cascade, `VideoDeleteService` (path containment), the six phase
  services that wipe-and-repopulate against it, `VideoPhaseRunnerService`, `VideosController` /
  `VideoArtifactsController`, and `VideoStatus` in `core/domain.ts`.
- **Does not hit:** the file on disk. That is `filePath`, removed after the commit and only inside
  the storage root — a row delete that fails leaves bytes, never the reverse.

## Surfaces

| Surface | Role |
|---|---|
| `PersistPhase` | writes (creates the row) |
| every optional phase | reads; wipes and repopulates its own child table |
| console `features/videos` | reads, deletes |

## See

- Source: `.../videos/domain/Video.java`, `.../videos/service/VideoDeleteService.java`
- [docs/vidingest/VidIngest - Data Model.md](../../../vidingest/VidIngest%20-%20Data%20Model.md)
