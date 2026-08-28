---
type: process
universe: live
status: verified
verified: 2026-08-28
commit: 0a40fa2
consumes: [YoutubeChannel]
produces: [YoutubeChannelVideo]
---

# channel-sync

Listing what a YouTube channel currently publishes. It creates **candidates**, never videos —
ingestion is a separate, explicit call.

## Input → Movement → Output

A channel URL. yt-dlp fetches the playlist; each entry is upserted as a `YoutubeChannelVideo` with
`lastSeenAt` refreshed. Output is a candidate list a human or the console then chooses from.

## Why this shape

`syncChannel` is deliberately **not** `@Transactional`: `discover` is a yt-dlp playlist fetch and
the pool is 10 connections. Same rule as every phase — never hold a transaction across a subprocess
or HTTP call (`YoutubeChannelCommandService.java:142-151`).

Sync and ingest are separated because a channel with 400 videos would otherwise turn one cron tick
into 400 pipeline runs.

## Steps

1. `YoutubeChannelSyncScheduler.tick` on `vidingest.youtube.sync.cron` (default `0 0/30 * * * *`) —
   `YoutubeChannelSyncScheduler.java:53-54`. An `AtomicBoolean` guard and a `Semaphore` bound
   overlapping ticks — `:28`, `:38`.
2. `YoutubeChannelCommandService.syncChannel(channelId)` — `:151`. Writes `lastSyncAttemptAt` and
   `status = SYNCING`.
3. `YoutubeChannelDiscoveryService.discover(url, playlistLimit, timeoutSeconds)` runs yt-dlp —
   `YoutubeChannelDiscoveryService.java:19`.
4. Candidates upserted; `lastSyncSuccessAt` or `lastError` + `status = ERROR` written.
5. Ingestion is separate: `YoutubeChannelCommandService.createPipelineRun(channelId, request)` —
   `:251` — which enters [ingest-run](ingest-run.md).

## If you change this

- **Hits:** `YoutubeSyncProperties`, `YoutubeSyncExecutorConfig`, `YoutubeChannelDiscoveryParser`
  (yt-dlp output shape), console channel detail.
- **Does not hit:** [Video](../objects/media/video.md). There is no FK from a candidate to an
  ingested video — a synced candidate and an ingested one are indistinguishable in the schema.

## Surfaces

| Surface | Role |
|---|---|
| `YoutubeChannelSyncScheduler` | cron; **disabled in integration tests** |
| `YoutubeChannelsController` | manual sync + ingest |
| yt-dlp (local process) | produces the listing |

## See

- Objects: [YoutubeChannel](../objects/media/youtube-channel.md), [YoutubeChannelVideo](../objects/media/youtube-channel-video.md)
- [docs/vidingest/VidIngest - YouTube Channels.md](../../vidingest/VidIngest%20-%20YouTube%20Channels.md)
