# VidIngest - MCP with LM Studio

This page shows how to use VidIngest MCP tools with a model you run in LM Studio.

## quick answer

- Keep `vidingest-server` running on `http://localhost:8051/vidingest` (REST).
- Keep `vidingest-mcp` running on `http://localhost:8055/vidingest` (MCP over SSE).
- Use an MCP-capable client (Cursor, Claude Desktop with MCP bridge, or any MCP client).
- Point the client to VidIngest SSE MCP endpoint.
- Select your LM Studio model as the chat/completion model in that client.

## what this setup is

- **MCP server**: `vidingest-mcp` Spring AI MCP endpoint at `localhost:8055`.
- **Model runtime**: LM Studio (local model inference).
- **MCP client**: Your IDE/chat client that supports MCP and tool calling.

LM Studio runs the model; MCP servers provide tools. You connect both in the MCP client.

## prerequisites

- VidIngest service up:
  - `./mvnw -pl applications/vidingest/vidingest-server spring-boot:run`
  - `./mvnw -pl applications/vidingest/vidingest-mcp spring-boot:run`
  - Or: `./scripts/tradey.sh start vidingest` then `./scripts/tradey.sh start mcp`
- PostgreSQL running on the configured host/port (default: `localhost:3030`)
- yt-dlp installed (`pip install yt-dlp`)
- Whisper ASR running if you want transcription (docker infra exposes `http://localhost:9000/docs`)
- LM Studio running with a loaded model.
- An MCP-capable client configured to use the LM Studio model.

## json config (`mcpServers`)

Use this as your base configuration:

```json
{
  "mcpServers": {
    "vidingest": {
      "url": "http://localhost:8055/vidingest/sse"
    }
  }
}
```

Use the full SSE URL, not only the service root path.

If you run VidIngest via Docker compose, the host-exposed MCP port is `8055`:

```json
{
  "mcpServers": {
    "vidingest": {
      "url": "http://localhost:8055/vidingest/sse"
    }
  }
}
```

### docker example

From another container on the `video-knowledge` network, address the MCP server by
service name instead of `localhost`:

```json
{
  "mcpServers": {
    "vidingest": {
      "url": "http://vidingest-mcp:8055/vidingest/sse"
    }
  }
}
```

## available tools

| Tool | Description |
|------|-------------|
| `createPipelineRuns` | Create async pipeline runs for one or more yt-dlp-supported URLs |
| `downloadToDisk` | Download a video to disk with channel folder structure (no database) |
| `downloadToDatabase` | Download and persist metadata without the full pipeline |
| `listVideos` | List all ingested videos with ID, title, source, and status |
| `getVideoStatus` | Get detailed status of a video by UUID |
| `deleteVideo` | Delete a video, its file on disk, and cascading DB records |
| `listPipelineRuns` | List pipeline runs (paged) |
| `searchVideos` | Run semantic pgvector search over context chunks |
| `retryPipelineRun` | Retry a failed pipeline run by UUID (async) |

## tool parameters and responses

### `createPipelineRuns`

Parameters:
- `urls` (List<String>, required): Video URLs (YouTube, Vimeo, or any yt-dlp-supported platform)
- `skipPhases` (Set<String>, optional): Optional phases to leave out of this run — `TRANSCRIBE`, `DIARIZE`, `FRAME_SAMPLE`, `OCR`, `FUSE`, `KNOWLEDGE`, `CONTEXT`. Empty or omitted runs everything the deployment has enabled.

Unless `TRANSCRIBE` is in `skipPhases`, VidIngest calls Whisper (`/asr?output=json`), persists transcription rows, and writes transcript sidecars next to the downloaded video file under `package/vidingest/videos/...`.

Returns:

- `runId`: String UUID for the created pipeline run
- `items`: Array of per-URL results:
  - `url`: submitted URL
  - `status`: `ACCEPTED | REJECTED`
  - `itemId`: UUID (present when `ACCEPTED`; persisted run-item id)
  - `reason`: String (present when `REJECTED`)

### `downloadToDisk`

Parameters:
- `url` (String, required): Video URL

Returns: `{ videoPath, metadataPath }`

### `downloadToDatabase`

Parameters:
- `url` (String, required): Video URL

Returns: `{ id, title, source, sourceVideoId, status, filePath, channelName, createdAt }`

### `listVideos`

No parameters.

Returns: Array of `{ id, title, source, sourceVideoId, status, filePath, channelName, createdAt }`

### `getVideoStatus`

Parameters:
- `videoId` (String, required): Video UUID

Returns: `{ id, title, source, sourceVideoId, status, filePath, channelName, createdAt }`

### `deleteVideo`

Parameters:
- `videoId` (String, required): Video UUID

Returns: `{ status: "deleted", videoId }`

### `listPipelineRuns`

Parameters:

- `status` (String, optional): `PENDING`, `IN_PROGRESS`, `COMPLETED`, `FAILED`, `CANCELLED`, or `ALL`
- `page` (Integer, optional): 0-based page index
- `size` (Integer, optional): page size

Returns: `{ items, page, size, total }` where `items` is an array of:

- `id`, `status`, `phase`, `errorCode`, `error`, `createdAt`, `updatedAt`
- `videoCount` (int): number of videos produced by this run
- `videoId`, `channelName`, `videoTitle` (optional): stable preview video fields (useful for list rows)
- `videoUrl` (optional): legacy single-video runs may still carry the original URL here

### `searchVideos`

Parameters:
- `query` (String, required): Natural language query
- `limit` (int, required): Max chunk matches (1-50)

Returns: Array of `{ chunkId, videoId, chunkIndex, snippet, videoTitle, channelName, filePath }`

### `retryPipelineRun`

Parameters:
- `pipelineId` (String, required): Failed pipeline run UUID (run id)
- `skipPhases` (Set<String>, optional): Same semantics as `createPipelineRuns`

Returns:

- `runId`: String UUID of the retried pipeline run (same run id)
- `items`: Array of per-item retry results (same shape as `createPipelineRuns.items`)

## implementation pointers

| File | Role |
|------|------|
| `applications/vidingest/vidingest-mcp/src/main/java/com/tradinglabs/vidingest/mcp/tools/McpIngestTools.java` | MCP tool definitions |
| `applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/pipeline/service/PipelineService.java` | Pipeline execution service |
| `applications/vidingest/vidingest-mcp/src/main/resources/application.properties` | MCP server configuration |

## troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Client can't connect to SSE endpoint | `vidingest-mcp` not running or wrong port | Verify service is up on port 8055 (local default) |
| `POST to /vidingest/sse` error | Client sending POST to SSE discovery (GET) endpoint | Use `/vidingest/mcp/message?sessionId=...` for JSON-RPC |
| Tool call timeout | yt-dlp download taking too long | Configure `vidingest.download.timeout-seconds` and increase client timeout for long files |
| `Video already ingested` error | Duplicate source+videoId pair | Expected behavior; use `listVideos` to check before ingesting |
| `Semantic search is disabled` error | `vidingest.search.semantic-enabled=false` | Set it to `true` and wire a query embedding provider |
| `TRANSCRIPTION_FAILURE` / `Whisper request failed` | Whisper not running or model still downloading | Start `whisper` and verify `http://localhost:9000/docs` |

## semantic search defaults (local runs)

By default, `vidingest-server` enables semantic search and uses **Ollama embeddings** on `http://localhost:11434`.

- To disable semantic search (and therefore skip context generation during ingestion): set `vidingest.search.semantic-enabled=false` or include `CONTEXT` in `skipPhases`.
- To switch embedding providers (e.g., LM Studio OpenAI-compatible): set `vidingest.search.embeddings.provider=openai-compatible` and configure `vidingest.search.embeddings.base-url`.

## Spring AI MCP client wiring

To connect a Spring AI application to VidIngest as an MCP client:

```properties
# local/dev
spring.ai.mcp.client.sse.connections.vidingest.url=http://localhost:8055/vidingest/sse

# docker
spring.ai.mcp.client.sse.connections.vidingest.url=http://vidingest-mcp:8055/vidingest/sse

# prod
spring.ai.mcp.client.sse.connections.vidingest.url=${MCP_VIDINGEST_URL:http://vidingest-mcp:8055/vidingest/sse}
```

## M2–M8 knowledge-extraction tools

Seven additional tools are exposed in M8. They delegate to the matching REST endpoints
on `vidingest-server` and require the corresponding pipeline phases to have run
successfully (or, for `searchKnowledge`, that `vidingest.search.semantic-enabled=true`
plus a populated `vidingest_knowledge_units` table). All listed on the same MCP endpoint
as the original nine — no separate tool group.

| Tool | Parameters | Notes |
|------|------------|-------|
| `searchKnowledge`      | `query` (text), `type` (ENTITY/TOPIC/SUMMARY/CLAIM/QUESTION, optional), `limit` (1–50) | pgvector similarity across all videos; returns title/snippet/timing. |
| `getKnowledgeUnits`    | `videoId` (UUID), `type` (optional) | All units for one video, ordered by creation. |
| `regenerateKnowledge`  | `videoId` (UUID) | Re-runs M6 KnowledgePhase in isolation; idempotent. |
| `getSpeakers`          | `videoId` (UUID) | Speakers + per-speaker transcript-segment counts. |
| `renameSpeaker`        | `speakerId` (UUID), `displayName` (string, empty clears) | Operator-supplied friendly name; pyannote label is immutable. |
| `getMultimodalTimeline`| `videoId`, `fromSeconds` (optional), `toSeconds` (optional) | Fused per-window rows; both bounds null = whole video. |
| `getOcrResults`        | `videoId` (UUID) | OCR detections grouped by parent frame, ordered by frame timestamp. |

`createPipelineRuns` and `retryPipelineRun` take a single `skipPhases` list naming the
optional phases to leave out of the run (`TRANSCRIBE`, `DIARIZE`, `FRAME_SAMPLE`, `OCR`,
`FUSE`, `KNOWLEDGE`, `CONTEXT`). Omit it or pass an empty list to run everything the
deployment has enabled; naming a mandatory phase is rejected. This replaced six positional
booleans, so calls written against the older tool signature need updating — a request still
carrying them is a 400 naming the offending property, not a silently ignored field.

See [Knowledge Extraction](VidIngest%20-%20Knowledge%20Extraction.md) for the
underlying pipeline phases and configuration.

## related pages

- [VidIngest](VidIngest.md)
- [VidIngest - CLI Commands](VidIngest%20-%20CLI%20Commands.md)
- [VidIngest - Knowledge Extraction](VidIngest%20-%20Knowledge%20Extraction.md)
- Shared MCP autoconfiguration: `libraries/common-mcp-configs`
