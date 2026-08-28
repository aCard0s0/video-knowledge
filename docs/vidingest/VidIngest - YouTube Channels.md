---
type: reference
last_reviewed: 2026-05-12
---

# VidIngest - YouTube channels

**Owner**: TradingLabs Platform  
**Status**: draft  

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
```

## Behavior and invariants

- **Bounded discovery**: sync only fetches a bounded number of recent entries (`playlistLimit`) to keep runtime predictable on large channels.
- **Catalog vs ingestion**: `vidingest_youtube_channel_videos` is a discovery catalog; ingestion dedupe remains `(source, source_video_id)` on `vidingest_videos`.
- **Idempotency**: per-channel uniqueness is enforced by `(channel_id, youtube_video_id)`.

## Testing and validation

- Unit:
  - `YoutubeChannelDiscoveryParserTest` validates yt-dlp JSON parsing.
- Integration:
  - `YoutubeChannelsApiIntegrationTest` validates create + sync (with discovery mocked) + list videos.

