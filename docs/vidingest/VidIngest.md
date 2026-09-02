---
type: overview
last_reviewed: 2026-08-29
---

# VidIngest (server + MCP + CLI)

- **Owner**: TradingLabs Platform
- **Last reviewed**: 2026-08-27
- **Status**: stable
- **Applies to**: `vidingest-server`, `vidingest-mcp`, `vidingest-cli`, `vidingest-client`, `vidingest-api`
- **Capabilities**: download + transcription + semantic chunk search (stable);
  speaker diarization + frame sampling + OCR + multimodal fusion + LLM-driven knowledge
  extraction (disabled by default — see
  [Knowledge Extraction](VidIngest%20-%20Knowledge%20Extraction.md))
- **Source of truth**:
  - Server: `applications/vidingest/vidingest-server/`
  - MCP: `applications/vidingest/vidingest-mcp/`
  - CLI: `applications/vidingest/vidingest-cli/`
  - Client: `applications/vidingest/vidingest-client/`

## Quickstart (for agents)

VidIngest is split into:

- **`vidingest-server`**: Spring MVC server that runs the ingestion pipeline, owns the DB + Liquibase schema, executes `yt-dlp`, exposes **REST**.
- **`vidingest-mcp`**: Standalone Spring MVC MCP server (SSE) that delegates to `vidingest-server` via `vidingest-client`.
- **`vidingest-cli`**: Spring Shell CLI (remote-only) that calls the server over HTTP via `vidingest-client`.
- **`vidingest-client`**: typed `RestClient` wrapper used by the CLI (and any future Java consumers).

**Entrypoints**

| Area | Path |
|------|------|
| Server main class | `applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/VidIngestApp.java` |
| Server pipeline service | `applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/pipeline/service/PipelineService.java` |
| Server config | `applications/vidingest/vidingest-server/src/main/resources/application.properties` |
| Server schema | `applications/vidingest/vidingest-server/src/main/resources/db/changelog/db.changelog-master.yaml` |
| REST controllers | `applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/pipeline/controller/`, `applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/videos/controller/`, `applications/vidingest/vidingest-server/src/main/java/com/tradinglabs/vidingest/search/controller/` |
| MCP main class | `applications/vidingest/vidingest-mcp/src/main/java/com/tradinglabs/vidingest/mcp/VidIngestMcpApplication.java` |
| MCP tools | `applications/vidingest/vidingest-mcp/src/main/java/com/tradinglabs/vidingest/mcp/tools/McpIngestTools.java` |
| MCP config | `applications/vidingest/vidingest-mcp/src/main/resources/application.properties` |
| CLI main class | `applications/vidingest/vidingest-cli/src/main/java/com/tradinglabs/vidingest/VidingestCliApp.java` |
| CLI commands | `applications/vidingest/vidingest-cli/src/main/java/com/tradinglabs/vidingest/cli/IngestCommands.java` |
| Typed client | `applications/vidingest/vidingest-client/src/main/java/com/tradinglabs/vidingest/client/VidingestClient.java` |

**Run and validate (local)**

```bash
# From repo root
./mvnw -pl applications/vidingest/vidingest-server spring-boot:run

# In another terminal (MCP server delegates to vidingest-server)
./mvnw -pl applications/vidingest/vidingest-mcp spring-boot:run

# In another terminal (CLI points to server)
./mvnw -pl applications/vidingest/vidingest-cli spring-boot:run

# Docker (recommended for end-to-end)
./vk start vidingest --build && ./vk start mcp
```

**When you change this app, update**

- This file and sibling docs under `docs/vidingest/`
- Diagrams in `docs/vidingest/diagrams/` (both SVG and Mermaid source)

## Scope and non-goals

**In scope**

- Video download from YouTube, Vimeo, and other yt-dlp-supported platforms
- Metadata extraction and persistence to PostgreSQL
- Transcription via Whisper (speech-to-text persisted to DB and written to disk)
- Context chunk generation + embeddings for semantic search (pgvector)
- Batch ingestion from URL files
- Disk-only download mode with channel folder structure
- Pipeline run tracking for the ingestion pipeline
- Database schema management via Liquibase
- Operator web console (`../../applications/webapp/`, Angular) — see [Web UI](VidIngest%20-%20Web%20UI.md)

**Out of scope (handled elsewhere or planned)**

- Authentication and multi-user access (the server has no Spring Security; the console assumes a
  single operator on localhost)

## Key concepts

| Term | Definition |
|------|-----------|
| Video | A downloaded video with metadata stored in `vidingest_videos` |
| PipelineRun | Tracks the lifecycle of a full ingest pipeline execution |
| PipelineRunItem | One URL within a batch `PipelineRun`; tracks per-item status/phase and links to a produced `Video` |
| Transcription | Speech-to-text output linked to a video (`vidingest_transcriptions` + segments) |
| ContextChunk | Text chunk with a 1536-dimension embedding for semantic search (`vidingest_context_chunks`) |
| Disk-only mode | Downloads video + metadata JSON to disk without touching the database |
| YouTube channel catalog | A synced catalog of channel uploads for browsing/selection (`vidingest_youtube_channels` + `vidingest_youtube_channel_videos`) |

## Architecture

![VidIngest overview](diagrams/svg/vidingest-overview.svg)

Mermaid source: `diagrams/mermaid/vidingest-overview.mmd`

### High-level flow

1. User issues a command via Spring Shell (`vidingest-cli`) or a remote agent calls an MCP tool
2. `vidingest-cli` calls `vidingest-server` over REST (typed `vidingest-client`)
3. `vidingest-mcp` exposes MCP tools over SSE and delegates to `vidingest-server` over REST (typed `vidingest-client`)
4. `PipelineService` calls `VideoDownloadService` to extract metadata and download
5. `VideoDownloadService` shells out to `yt-dlp` via Apache Commons Exec
6. `MetadataService` maps yt-dlp JSON to `Video` entity and persists via JPA
7. Server extracts audio via `ffmpeg` and calls the Whisper ASR webservice (`/asr?output=json`)
8. Server persists `Transcription` + `TranscriptionSegment` records and writes transcript sidecars next to the video file on disk
9. When semantic search is enabled, server generates context chunks and embeddings for pgvector search
10. `PipelineService` tracks progress through `PipelineRun` entity

### Key entrypoints by area

| Area | Class | Package |
|------|-------|---------|
| CLI | `IngestCommands` | `cli` |
| MCP tools (`vidingest-mcp`) | `McpIngestTools` | `mcp.tools` |
| Pipeline | `IngestionService` | `ingestion.service` |
| Download | `VideoDownloadService` | `core.download.service` |
| Metadata | `MetadataService` | `core.download.service` |
| Command builder | `YtDlpCommandBuilder` | `core.download.util` |
| Command executor | `YtDlpExecutor` | `core.download.util` |
| Metadata parsing | `MetadataExtractor` | `core.download.util` |
| File helpers | `FileSystemHelper` | `core.download.util` |
| Path resolution | `ProjectPathResolver` | `config` |

## Where everything else lives

This page is the overview: what VidIngest is, what it is not, and how the pieces fit. It carries no
configuration, no tool tables and no runbook — each of those has a page that owns it, and a copy
here is a copy that drifts.

| Ask | Page |
|---|---|
| every property, profile, Docker setting, failure mode | [Config and Runtime](VidIngest%20-%20Config%20and%20Runtime.md) |
| tables, columns, enums, migrations | [Data Model](VidIngest%20-%20Data%20Model.md) |
| yt-dlp, download modes, storage paths, transcript artifacts | [Download Pipeline](VidIngest%20-%20Download%20Pipeline.md) |
| the enrichment phases and their sidecars | [Knowledge Extraction](VidIngest%20-%20Knowledge%20Extraction.md) |
| re-running one phase on one video | [Per-Phase Rerun](VidIngest%20-%20Per-Phase%20Rerun.md) |
| watching a channel | [YouTube Channels](VidIngest%20-%20YouTube%20Channels.md) |
| MCP tools and LM Studio setup | [MCP with LM Studio](VidIngest%20-%20MCP%20with%20LM%20Studio.md) |
| shell commands | [CLI Commands](VidIngest%20-%20CLI%20Commands.md) |
| the operator console | [Web UI](VidIngest%20-%20Web%20UI.md) |
| what to test | [Test Scenarios](VidIngest%20-%20Test%20Scenarios.md) |
| **what a change hits** | [docs/map](../map/CLAUDE.md) |

The full catalog — including the two Web UI sub-pages — is [docs/Home.md](../Home.md). This page
deliberately does not keep a second index; the one it used to carry listed five of thirteen pages.

## Change checklist

- [ ] Update this page if the architecture or the scope changes — **not** if a property, command or
      endpoint changes; those belong to the pages above
- [ ] Update the page that owns whatever you changed, and its `last_reviewed`
- [ ] Update diagrams (Mermaid source, then `./scripts/regenerate-mermaid-svgs.sh`)
- [ ] `python3 scripts/check-markdown-links.py`
