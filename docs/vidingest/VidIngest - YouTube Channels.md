---
type: reference
last_reviewed: 2026-08-29
---

# VidIngest - YouTube channels

**Owner**: TradingLabs Platform  
**Status**: shipped (REST + console screen at `/vidingest/channels`)  

**Applies to**:
- `vidingest-server`

**Source of truth**:
- Code:
  - `applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/youtube/`
- Config:
  - `applications/vidingest/vidingest-server/src/main/resources/application.properties`

## Quickstart (for agents)

This feature lets you add a YouTube channel URL, periodically sync its “available videos” list (via `yt-dlp`), browse that list over REST, and start pipeline runs for selected videos.

If you need to change YouTube discovery or sync behavior, start here:
- `com.tradinglabs.vidingest.youtube.discovery.YoutubeChannelDiscoveryService`
- `com.tradinglabs.vidingest.youtube.scheduler.YoutubeChannelSyncScheduler`
- `com.tradinglabs.vidingest.youtube.controller.YoutubeChannelsController`

Run and validate (Docker; from repo root):

```bash
./scripts/tradey.sh start vidingest
./scripts/tradey.sh status
```

Validate (REST):
- Add channel: `POST /vidingest/api/v1/youtube/channels`
- Sync: `POST /vidingest/api/v1/youtube/channels/{channelId}/sync`
- List videos: `GET /vidingest/api/v1/youtube/channels/{channelId}/videos?page=0&size=50`

## Scope and non-goals

- **In scope**: channel URL persistence, bounded discovery of recent uploads, REST browsing + selection, periodic sync via `@Scheduled`.
- **Not in scope**: YouTube Data API quotas/OAuth; full-history channel backfills; distributed leader election for multi-instance sync.

## Architecture

### Key entrypoints (code pointers)

- **REST**: `applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/youtube/controller/YoutubeChannelsController.java`
- **Persistence**:
  - `com.tradinglabs.vidingest.youtube.domain.YoutubeChannel`
  - `com.tradinglabs.vidingest.youtube.domain.YoutubeChannelVideo`
  - Liquibase: `applications/vidingest/vidingest-server/src/main/resources/db/changelog/changesets/006-youtube-channels.sql`
- **Discovery (yt-dlp)**:
  - Command builder: `com.tradinglabs.vidingest.core.download.util.YtDlpCommandBuilder#buildChannelListingCommand`
  - Executor: `com.tradinglabs.vidingest.core.download.util.YtDlpExecutor`
  - Parser: `com.tradinglabs.vidingest.youtube.discovery.YoutubeChannelDiscoveryParser`
- **Sync job**: `com.tradinglabs.vidingest.youtube.scheduler.YoutubeChannelSyncScheduler`

### Data flow (high-signal)

- A client adds a channel URL → VidIngest persists `vidingest_youtube_channels`
- Sync (manual or scheduled) runs yt-dlp “flat playlist” discovery → upserts `vidingest_youtube_channel_videos`
- A client lists channel videos → selects IDs → VidIngest expands to watch URLs → `POST /api/v1/pipelines`

## Interfaces

### REST

- `POST /api/v1/youtube/channels`

Request:
```json
{
  "url": "https://www.youtube.com/@TradingLabs",
  "displayName": "TradingLabs"
}
```

- `GET /api/v1/youtube/channels?page=0&size=50` — `listChannels`
- `GET /api/v1/youtube/channels/{channelId}` — `getChannel`
- `DELETE /api/v1/youtube/channels/{channelId}` — `deleteChannel`, **204**. See *Deleting a channel* below.
- `POST /api/v1/youtube/channels/{channelId}/sync`
- `GET /api/v1/youtube/channels/{channelId}/videos?page=0&size=50`
- `POST /api/v1/youtube/channels/{channelId}/pipelines`

Request:
```json
{
  "youtubeVideoIds": ["dQw4w9WgXcQ"],
  "skipPhases": ["DIARIZE", "FRAME_SAMPLE", "OCR", "KNOWLEDGE"]
}
```

## Configuration

Where it lives:
- `applications/vidingest/vidingest-server/src/main/resources/application.properties`

Key properties:
```properties
vidingest.youtube.sync.enabled=true
vidingest.youtube.sync.cron=0 0/30 * * * *
vidingest.youtube.sync.playlistLimit=200
vidingest.youtube.sync.timeoutSeconds=120
vidingest.youtube.sync.concurrency=${VIDINGEST_YOUTUBE_SYNC_CONCURRENCY:4}
```

Bound by `YoutubeSyncProperties`. `concurrency` caps how many channels one tick syncs at once — the
scheduler holds a `Semaphore` of that size plus an `AtomicBoolean` so a slow tick cannot overlap
itself.

## Behavior and invariants

- **Bounded discovery**: sync only fetches a bounded number of recent entries (`playlistLimit`) to keep runtime predictable on large channels.
- **Catalog vs ingestion**: `vidingest_youtube_channel_videos` is a discovery catalog; ingestion dedupe remains `(source, source_video_id)` on `vidingest_videos`.
- **Idempotency**: per-channel uniqueness is enforced by `(channel_id, youtube_video_id)`.
- **No FK from a candidate to an ingested video.** A `vidingest_youtube_channel_videos` row that has
  been ingested looks exactly like one that has not; the only link is the URL string.
- **`syncChannel` is deliberately not `@Transactional`.** Discovery is a yt-dlp playlist fetch and
  the pool is 10 connections — the same rule every pipeline phase follows
  (`YoutubeChannelCommandService`, javadoc above `syncChannel`).
- **Sync bookkeeping is three fields, not one**: `lastSyncAttemptAt`, `lastSyncSuccessAt` and
  `lastError`. A single "last synced" cannot answer *is it broken, or just quiet*.

### Status lifecycle

`NEW → SYNCING → READY`, or `ERROR` with `lastError` set. Four constants, and **there is no
disabled state.** A fifth, `DISABLED`, was declared and never reachable — nothing set it and no
endpoint produced it — while two guards branched on it: the scheduler swept
`findAllByStatusNot(DISABLED)` and `loadForSync` 409'd on one. Both were dead, and constant and
guards were removed together; the sweep is a plain `findAll()`.

### Deleting a channel

Having no disable is why `DELETE` exists: a mistyped URL sat `ERROR` forever while the
half-hour sweep re-ran yt-dlp against a dead address. Delete drops the discovered catalog through
the `ON DELETE CASCADE` on `vidingest_youtube_channel_videos.channel_id`; **videos already ingested
are untouched** — they are `vidingest_videos` rows with no FK back here. Removing a channel undoes a
typo; it does not wipe a corpus. The console arms the row before sending for that reason.

## Testing and validation

- Unit:
  - `YoutubeChannelDiscoveryParserTest` validates yt-dlp JSON parsing.
- Integration:
  - `YoutubeChannelsApiIntegrationTest` validates create + sync (with discovery mocked) + list videos.
  - `YoutubeChannelPipelinesApiIntegrationTest` validates starting a run from selected channel videos.

Integration tests disable the scheduler; `BaseVidingestIntegrationTest` turns YouTube sync off.

## Related pages

- What a change here hits: [YoutubeChannel](../map/objects/media/youtube-channel.md),
  [YoutubeChannelVideo](../map/objects/media/youtube-channel-video.md),
  [channel-sync](../map/processes/channel-sync.md)
- Where the run goes next: [VidIngest - Download Pipeline](VidIngest%20-%20Download%20Pipeline.md)
- Console screen: [VidIngest - Web UI](VidIngest%20-%20Web%20UI.md)
