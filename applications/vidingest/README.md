# VidIngest

VidIngest is the video ingestion pipeline (server + MCP + CLI + client). It downloads
videos (via `yt-dlp`), extracts metadata, persists state to PostgreSQL, and exposes
REST + MCP tooling for automation.

- **REST base URL (Docker/vk default)**: `http://localhost:8051/vidingest`
- **Readiness check**: `http://localhost:8051/vidingest/api/v1/health/ready`
- **MCP (SSE)**: `http://localhost:8055/vidingest/sse`

## Quickstart

```bash
./vk start vidingest --build
```

```bash
./vk start mcp
```

Validate quickly:

```bash
curl -fsS "http://localhost:8051/vidingest/api/v1/videos"
```

```bash
curl -s -N --max-time 2 "http://localhost:8055/vidingest/sse"
```

## Docs

- Overview: [VidIngest](../../docs/vidingest/VidIngest.md)
- CLI commands: [VidIngest - CLI Commands](../../docs/vidingest/VidIngest%20-%20CLI%20Commands.md)
- Download pipeline: [VidIngest - Download Pipeline](../../docs/vidingest/VidIngest%20-%20Download%20Pipeline.md)
- Data model: [VidIngest - Data Model](../../docs/vidingest/VidIngest%20-%20Data%20Model.md)
- Config and runtime: [VidIngest - Config and Runtime](../../docs/vidingest/VidIngest%20-%20Config%20and%20Runtime.md)
- MCP setup: [VidIngest - MCP with LM Studio](../../docs/vidingest/VidIngest%20-%20MCP%20with%20LM%20Studio.md)

## What lives here

- `vidingest-api`: shared DTOs + API path constants
- `vidingest-server`: Spring Boot service (REST + ingestion pipeline)
- `vidingest-client`: typed Java HTTP client for the server
- `vidingest-mcp`: standalone MCP server (SSE) delegating to `vidingest-server` via `vidingest-client`
- `vidingest-cli`: Spring Shell CLI
- `http/vidingest-server.http`: request collection for the REST API
