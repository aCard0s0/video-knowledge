# Video Knowledge

VidIngest — a video-to-knowledge ingestion backend. It downloads videos (via `yt-dlp`),
transcribes, diarizes and OCRs them, fuses the results into multimodal segments, and
extracts searchable knowledge units into PostgreSQL + pgvector.

## Modules

`applications/vidingest` is a Maven reactor (Spring Boot 4, Java 26):

| Module | Role |
|---|---|
| [vidingest-api](applications/vidingest/vidingest-api) | Shared DTOs and API path constants |
| [vidingest-server](applications/vidingest/vidingest-server) | REST service + ingestion pipeline (JPA, Liquibase, pgvector) |
| [vidingest-client](applications/vidingest/vidingest-client) | Typed Java HTTP client for the server |
| [vidingest-mcp](applications/vidingest/vidingest-mcp) | Standalone MCP (SSE) server delegating via the client |
| [vidingest-cli](applications/vidingest/vidingest-cli) | Spring Shell console |

Shared Java libraries live in [libraries](libraries) (`common-logging`, `common-web`,
`common-http-client-core`, `common-observability-web`, `common-operation-logging-web`,
`common-operation-logging-mcp`, `common-mcp-configs`).

## Quick start (Docker)

```bash
./scripts/tradey.sh start --build     # build + run infra + vidingest
```

```bash
./scripts/tradey.sh status            # compose ps with health
```

```bash
./scripts/tradey.sh logs -f vidingest # follow server logs
```

```bash
./scripts/tradey.sh cli               # open the VidIngest CLI
```

```bash
./scripts/tradey.sh start mcp         # MCP SSE server (opt-in)
```

```bash
./scripts/tradey.sh down --volumes    # tear everything down (incl. data)
```

- REST: <http://localhost:8051/vidingest>
- MCP (SSE): <http://localhost:8055/vidingest/sse>

`tradey` layers the split compose files (`compose.yml` + `compose/*`). Infra
(`timescaledb`, `ollama`, `whisper`) starts automatically as a dependency of the
server. Optional sidecars (`paddleocr-server` for OCR, `diarize-asr` for speaker
diarization) are opt-in: `./scripts/tradey.sh start sidecars`. Host ports are defined
in [compose/ports.env](compose/ports.env).

## Build (host)

```bash
./mvnw clean package
```

The Maven wrapper (`./mvnw`) pins the build; no system Maven required. Java 26 is
enforced by the root POM.

## Docs

See [docs/Home.md](docs/Home.md).
