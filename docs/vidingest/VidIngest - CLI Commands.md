---
type: reference
last_reviewed: 2026-08-29
---

# VidIngest - CLI Commands

- **Primary package**: `com.tradinglabs.vidingest.cli`
- **Last reviewed**: 2026-03-15
- **Status**: stable

## Quickstart (for agents)

All CLI commands are defined in `IngestCommands.java` and exposed via Spring Shell.

The CLI is **remote-only**; it calls a running `vidingest-server` via `vidingest-client`.

- Default server base URL: `vidingest.server.base-url=http://localhost:8051/vidingest`
- Platform default (running alongside other services): `http://localhost:8051/vidingest`

**Implementation pointers**

| File | Role |
|------|------|
| `applications/vidingest/vidingest-cli/src/main/java/com/tradinglabs/vidingest/cli/IngestCommands.java` | Shell command definitions |
| `applications/vidingest/vidingest-client/src/main/java/com/tradinglabs/vidingest/client/VidingestClient.java` | Typed REST client used by the CLI |
| `applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/` | Server REST controllers (`/vidingest/api/v1/...`) |

## Commands

### `ingest`

Download and ingest a video from a URL. Creates a pipeline run, downloads the video, extracts metadata, and persists everything to the database.
If `--skip-transcription false`, the server also transcribes audio via Whisper and persists `vidingest_transcriptions` + segments, plus writes transcript files under `package/vidingest/transcriptions/`.

```
ingest --url <VIDEO_URL> [--config <PATH>] [--skip-transcription] [--dry-run]
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `--url` | String | required | Video URL (YouTube, Vimeo, etc.) |
| `--config` | String | null | Config file path (reserved for future use) |
| `--skip-phases` | String | `DIARIZE,FRAME_SAMPLE,OCR,KNOWLEDGE` | Comma-separated optional phases to skip: `TRANSCRIBE, DIARIZE, FRAME_SAMPLE, OCR, FUSE, KNOWLEDGE, CONTEXT`. Pass an empty string to run every enabled phase. |
| `--dry-run` | boolean | false | Validate URL without downloading |

**Examples**

```
shell:> ingest --url https://www.youtube.com/watch?v=dQw4w9WgXcQ
Ingestion complete!
Video ID: a1b2c3d4-e5f6-7890-abcd-ef1234567890
Title: Rick Astley - Never Gonna Give You Up
File: /path/to/videos/dQw4w9WgXcQ.mp4

shell:> ingest --url https://www.youtube.com/watch?v=dQw4w9WgXcQ --dry-run true
Dry run successful. Video URL is valid.
```

**Error responses**

- `ERROR [Ingestion]: Video already ingested: youtube/dQw4w9WgXcQ` - duplicate detection
- `ERROR [Ingestion]: yt-dlp failed with exit code 1. Error: ...` - download failure

### `ingest-file`

Batch ingest from a text file containing one URL per line. Lines starting with `#` and blank lines are skipped. Each URL is processed independently through the orchestrator.

```
ingest-file --file <PATH> [--config <PATH>]
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `--file` | String | required | Path to file with URLs (one per line) |
| `--config` | String | null | Config file path (reserved for future use) |

**Example file format**

```
# Trading education videos
https://www.youtube.com/watch?v=VIDEO_ID_1
https://www.youtube.com/watch?v=VIDEO_ID_2

# Market analysis
https://www.youtube.com/watch?v=VIDEO_ID_3
```

**Example output**

```
shell:> ingest-file --file /path/to/urls.txt
Batch ingestion complete: 3 total, 2 successful, 1 failed
```

### `download`

Download a video without running the full ingestion pipeline.

```
download --url <VIDEO_URL> [--disk-only] [--progress]
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `--url` | String | required | Video URL |
| `--disk-only` | boolean | false | Save to disk only (no database) |
| `--progress` | boolean | false | Request progress; server-side logs will contain output (CLI does not stream server stdout) |

**Database mode** (default): Downloads the video, extracts metadata, persists a `Video` entity, and writes a companion `.metadata.json` file next to the downloaded video.

```
shell:> download --url https://www.youtube.com/watch?v=VIDEO_ID
Download complete:
Video ID: a1b2c3d4-e5f6-7890-abcd-ef1234567890
Title: Video Title
File: /path/to/videos/VIDEO_ID.mp4
```

**Disk-only mode**: Downloads into `{videoPath}/{channelName}/YYYYMMDD.title.mp4` with a companion `.metadata.json` file.

In remote-only mode, this happens on the **server host/container**, not on the CLI machine.

```
shell:> download --url https://www.youtube.com/watch?v=VIDEO_ID --disk-only true
Download complete (disk only):
Video: /path/to/videos/ChannelName/20260315.video-title.mp4
Metadata: /path/to/videos/ChannelName/20260315.video-title.metadata.json

shell:> download --url https://www.youtube.com/watch?v=VIDEO_ID --progress true
[download]  32.5% of 114.32MiB at 2.25MiB/s ETA 00:34
```

### `status`

Show the current status of a video by its UUID.

```
status --video-id <UUID>
```

**Example output**

```
shell:> status --video-id a1b2c3d4-e5f6-7890-abcd-ef1234567890
Video: a1b2c3d4-e5f6-7890-abcd-ef1234567890
Title: Video Title
Source: youtube
Status: COMPLETED
File: /path/to/videos/VIDEO_ID.mp4
Created: 2026-03-15T10:30:00
```

### `list`

List all ingested videos. Titles are truncated to 50 characters.

```
list
```

**Example output**

```
shell:> list
Found 2 videos:

ID: a1b2c3d4-... | Title: Rick Astley - Never Gonna Give You Up | Status: COMPLETED
ID: b2c3d4e5-... | Title: Trading Strategy Explained - How to Read Ca... | Status: DOWNLOADED
```

### `pipelines`

List pipeline runs, optionally filtered by status.

```
pipelines [--status <STATUS>]
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `--status` | String | ALL | Filter: PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED, or ALL |

**Example output**

```
shell:> pipelines
Found 3 pipeline runs:

ID: a1b2c3d4-... | Status: COMPLETED   | Phase: DONE       | Created: 2026-03-15T10:30:00
ID: b2c3d4e5-... | Status: FAILED      | Phase: DONE       | Created: 2026-03-15T10:25:00 | Error: yt-dlp failed with exit code 1...
ID: c3d4e5f6-... | Status: COMPLETED   | Phase: DONE       | Created: 2026-03-15T10:20:00

shell:> pipelines --status FAILED
Found 1 pipeline runs:

ID: b2c3d4e5-... | Status: FAILED      | Phase: DONE       | Created: 2026-03-15T10:25:00 | Error: yt-dlp failed with exit code 1...
```

### `search`

Run semantic search over `ContextChunk` embeddings in PostgreSQL/pgvector.

```
search --query <TEXT> [--limit <N>]
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `--query` | String | required | Natural language query |
| `--limit` | int | 5 | Maximum number of chunk matches (1-50) |

**Example output**

```
shell:> search --query "support zone breakout" --limit 2
Found 2 chunk matches:

Chunk ID: 4f5f...
Video ID: 2d3c...
Chunk: 7
Title: Support and Resistance Basics
Channel: Trading Labs
File: /path/to/videos/search001.mp4
Snippet: Price breaks above resistance after repeated support bounces...
```

`search` requires `vidingest.search.semantic-enabled=true` and a configured query embedding provider.

### `retry`

Retry a failed pipeline run by UUID.

```
retry --pipeline-id <UUID> [--skip-transcription] [--skip-context]
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `--pipeline-id` | String | required | Failed pipeline run UUID |
| `--skip-phases` | String | *(omitted)* | Comma-separated optional phases to skip. **Omit to retry with the phases the run itself was created with**; pass an empty string to run every enabled phase. Absent is not empty — see [Per-Phase Rerun](VidIngest%20-%20Per-Phase%20Rerun.md). |

**Example output**

```
shell:> retry --pipeline-id b2c3d4e5-f6a7-8901-bcde-f12345678901
Retry accepted.
Pipeline ID: b2c3d4e5-f6a7-8901-bcde-f12345678901
URL: https://example.com/video
```

### `delete`

Delete a video and its associated data (file on disk, cascading DB records).

```
delete --video-id <UUID> [--force]
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `--video-id` | String | required | Video UUID to delete |
| `--force` | boolean | false | Skip confirmation prompt |

**Example output**

```
shell:> delete --video-id a1b2c3d4-e5f6-7890-abcd-ef1234567890
About to delete video: Rick Astley - Never Gonna Give You Up (a1b2c3d4-e5f6-7890-abcd-ef1234567890)
Run again with --force true to confirm.

shell:> delete --video-id a1b2c3d4-e5f6-7890-abcd-ef1234567890 --force true
Video a1b2c3d4-e5f6-7890-abcd-ef1234567890 deleted successfully
```

### `vidingest-help`

Show VidIngest usage, commands, configuration summary, and examples.

```
vidingest-help
```

Displays the download tool, format, storage paths, and quick examples for common workflows.

## Behavior notes

- The CLI is a thin wrapper around the server REST API.
- Duplicate detection is by `(source, source_video_id)` pair. Re-ingesting the same video returns an error.
- Error output follows the format `ERROR [operation]: message`.
- URL validation rejects blank values and URLs that don't start with `http://` or `https://`.
- The `delete` command requires `--force true` to execute; without it, a confirmation prompt is shown.
- `retry` only accepts pipeline runs currently in `FAILED` status and uses the stored source URL from the original pipeline run.
- `search` is explicit when semantic search is disabled or no embedding provider is configured.

## Knowledge-extraction commands

Four commands ride on top of the enrichment phases. They require the matching
master switch to be enabled on the server side (and the matching pipeline phases to have
been run for the target video).

| Command | Usage | Output |
|---------|-------|--------|
| `search-knowledge --query <TEXT> [--type PROCEDURE\|ENTITY\|TOPIC\|SUMMARY\|CLAIM\|QUESTION] [--limit N]` | Cross-video semantic search over `vidingest_knowledge_units`. Returns one block per hit with type, title, snippet, parent video. | Requires semantic search enabled. |
| `knowledge --video-id <UUID> [--type PROCEDURE\|ENTITY\|TOPIC\|SUMMARY\|CLAIM\|QUESTION]` | All knowledge units for one video, optionally filtered by type. | |
| `regenerate-knowledge --video-id <UUID>` | Re-runs `KnowledgePhase` in isolation against the video's current multimodal segments. | Calls `POST /videos/{id}/phases/KNOWLEDGE/run`, the one per-phase rerun endpoint. |
| `speakers --video-id <UUID>` | Lists pyannote-identified speakers with per-speaker transcript segment counts. | |

The `ingest` and `retry` commands also gained `--skip-diarize`, `--skip-frames`,
`--skip-ocr`, `--skip-knowledge` flags (defaulting to `true`) — flip individual ones to
`false` to opt in to the corresponding enrichment phase for that run.

See [Knowledge Extraction](VidIngest%20-%20Knowledge%20Extraction.md) for the
underlying pipeline phases and how to enable them.

## Related pages

- [VidIngest](VidIngest.md)
- [VidIngest - Download Pipeline](VidIngest%20-%20Download%20Pipeline.md)
- [VidIngest - Knowledge Extraction](VidIngest%20-%20Knowledge%20Extraction.md)
