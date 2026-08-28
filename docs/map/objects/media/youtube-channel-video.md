---
type: object
cluster: media
universe: live
status: verified
verified: 2026-08-28
commit: 0a40fa2
entity: applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/youtube/domain/YoutubeChannelVideo.java
---

# YoutubeChannelVideo

A video **seen on a channel** — a candidate for ingestion, not an ingested thing. This is the row a
sync writes and the console lists when you pick what to ingest.

## Why this shape

- **`firstSeenAt` / `lastSeenAt` are both required** (`:62`, `:65`): a sync re-touches every video
  still on the playlist, so "when did this appear" and "is it still listed" are different questions
  and a single timestamp answers neither.
- **`watchUrl` is stored, not derived.** The pipeline takes a URL, and re-deriving it from
  `youtubeVideoId` would put YouTube's URL format inside the ingest path.

## Shape

- `vidingest_youtube_channel_videos` — `:29`
- `channel` `ManyToOne` non-optional, `youtubeVideoId` (64), `watchUrl`, `title`, `publishedAt` — `:43`–`:55`
- `metadata` `jsonb`, `firstSeenAt`, `lastSeenAt` — `:59`–`:65`

## Connected to

- **owned-by:** [YoutubeChannel](youtube-channel.md)
- **looks-like-but-is-not:** [Video](video.md). **No FK exists between them.** A candidate that has
  been ingested looks exactly like one that has not — the only link is the URL string.

## If you change this

- **Hits:** `YoutubeChannelVideoRepository`, `YoutubeChannelCommandService.listChannelVideos` /
  `createPipelineRun`, `YoutubeChannelMapper`, console channel detail.
- **Does not hit:** [Video](video.md) or any run. Deleting a candidate deletes nothing downstream.

## Surfaces

| Surface | Role |
|---|---|
| `YoutubeChannelCommandService.syncChannel` | writes (upsert per sync) |
| console channel detail | reads, selects for ingest |

## See

- Source: `.../youtube/domain/YoutubeChannelVideo.java`
