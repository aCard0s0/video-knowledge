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

The operator console is [applications/webapp](applications/webapp) — Angular 22, zoneless, built
into the server jar and served from the same origin at `/vidingest`. It is not a Maven module; see
its [README](applications/webapp/README.md) for the dev server and the generated API client.

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

- Console: <http://localhost:8051/vidingest>
- REST: <http://localhost:8051/vidingest/api/v1>
- MCP (SSE): <http://localhost:8055/vidingest/sse>

`tradey` layers the split compose files (`compose.yml` + `compose/*`). Infra
(`postgres`, `ollama`, `whisper`) starts automatically as a dependency of the
server. Optional sidecars (`paddleocr-server` for OCR, `diarize-asr` for speaker
diarization) are opt-in: `./scripts/tradey.sh start sidecars`. Host ports are defined
in [compose/ports.env](compose/ports.env).

## Build (host)

```bash
./mvnw clean package
```

The Maven wrapper (`./mvnw`) pins the build; no system Maven required. Java 26 and Maven 3.9+ are
enforced by the root POM. Note the wrapper resolves **Maven 4.0.0-rc-4** while the container images
install **3.9.11** (`MAVEN_VERSION` in each Dockerfile) — both satisfy the enforcer, so a host build
and an image build do not use the same Maven.

To work on the console:

```bash
cd applications/webapp && npm install && npm start
```

## Docs

See [docs/Home.md](docs/Home.md).
