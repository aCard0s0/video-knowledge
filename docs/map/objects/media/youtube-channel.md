---
type: object
cluster: media
universe: live
status: verified
verified: 2026-08-28
commit: 0a40fa2
entity: applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/youtube/domain/YoutubeChannel.java
---

# YoutubeChannel

A watched channel URL. Syncing it lists videos via yt-dlp and records them as candidates; it does
**not** ingest anything. Ingestion is a separate, explicit call.

## Why this shape

- **The sync record is three timestamps, not one.** `lastSyncAttemptAt` and `lastSyncSuccessAt` are
  separate so a channel that keeps failing still shows when it last worked, and `lastError` holds
  the reason (`:54-61`). A single "last synced" field cannot answer "is it broken or just quiet".
- **`syncChannel` is deliberately not `@Transactional`** — `discover` is a yt-dlp playlist fetch and
  the pool is 10 connections (`YoutubeChannelCommandService.java:142-151`). Same rule as the phases.
- **Identity is `UNIQUE (channel_url)`** — `006-youtube-channels.sql:20`.
- **There is no disabled state**, and the absence is deliberate. A fifth `DISABLED` constant was
  declared and never reachable — nothing set it, no endpoint produced it — while two guards branched
  on it, so both were dead. Constant and guards were removed together; the scheduler's sweep is a
  plain `findAll()`. `DELETE /youtube/channels/{channelId}` is how tracking stops.

## Shape

- `vidingest_youtube_channels` — `:28`
- `channelUrl` (unique), `displayName`, `status` `YoutubeChannelStatus` (`NEW, SYNCING, READY, ERROR`) — `:41`, `:44`, `:48`
- `metadata` `jsonb`, `lastSyncAttemptAt`, `lastSyncSuccessAt`, `lastError` — `:52`–`:61`
- Indexes: `status`, `last_sync_success_at DESC` — `006-youtube-channels.sql:23-24`

## Connected to

- **owns:** [YoutubeChannelVideo](youtube-channel-video.md)
- **looks-like-but-is-not:** a source of `Video` rows. Nothing links a channel to an ingested
  `Video` — the join is by URL at ingest time, and it is not stored.

## If you change this

- **Hits:** `YoutubeChannelCommandService`, `YoutubeChannelSyncScheduler`
  (`vidingest.youtube.sync.cron`, default `0 0/30 * * * *`), `YoutubeChannelMapper`,
  `YoutubeChannelsController`, `YoutubeChannelStatus` in `core/domain.ts`, console `features/channels`.
- **Does not hit:** the pipeline. A sync creates candidates; `createPipelineRun` on the channel is
  what starts work (`YoutubeChannelCommandService.java:251`).

## Surfaces

| Surface | Role |
|---|---|
| `YoutubeChannelSyncScheduler` | writes on a cron; integration tests disable it |
| `YoutubeChannelDiscoveryService` | reads yt-dlp output, writes nothing |
| console `features/channels` | reads, creates, deletes, triggers sync + ingest |

## See

- Source: `.../youtube/domain/YoutubeChannel.java`
- [docs/vidingest/VidIngest - YouTube Channels.md](../../../vidingest/VidIngest%20-%20YouTube%20Channels.md)
- [processes/channel-sync.md](../../processes/channel-sync.md)
