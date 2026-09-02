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

The operator console is [applications/webapp](applications/webapp) — Angular 22, zoneless. It is
its own nginx image on :8052, which proxies the API so the two stay same-origin; the server jar
stopped serving it in Aug 2026. Not a Maven module; see its
[README](applications/webapp/README.md) for the dev server and the generated API client.

Shared Java libraries live in [libraries](libraries) (`common-logging`, `common-web`,
`common-http-client-core`, `common-observability-web`, `common-operation-logging-web`,
`common-operation-logging-mcp`, `common-mcp-configs`).

## Quick start

[`./vk`](vk) is the one script that runs this repo. Bare, it prints its whole surface; every
command takes `--help`, and nothing but `-f` on `logs` and `dev` blocks.

```bash
./vk
```

```bash
./vk doctor            # can this machine operate the repo? exit 3 missing, 4 unusable
```

```bash
./vk setup             # fresh clone: install the console's npm dependencies
```

```bash
./vk start --build     # build the images, then converge the stack and wait for healthy
```

```bash
./vk status            # what is running, and the ports actually bound
```

```bash
./vk logs -f vidingest # follow server logs (the only command besides dev that blocks)
```

```bash
./vk console           # the VidIngest Spring Shell CLI
```

```bash
./vk down --volumes    # remove the containers and wipe the data (prompts; --yes to skip)
```

- Console: <http://localhost:8052/vidingest>
- REST: <http://localhost:8051/vidingest/api/v1>
- MCP (SSE): <http://localhost:8055/vidingest/sse> — opt-in, `./vk start vidingest-mcp`

`vk` layers the split compose files (`compose.yml` + `compose/*`). Infra is `postgres` and
nothing else: the model runtimes are **not** containers, they run on the host so inference
reaches the GPU (`./vk doctor` probes the host one). The two sidecars — `paddleocr-server` for
OCR, `diarize-asr` for diarization — are opt-in: `./vk start sidecars`. Targets are arguments to
verbs, never verbs; `./vk list` prints them. Host ports are defined in
[compose/ports.env](compose/ports.env).

## Reach it from your other devices (optional)

```bash
./vk start --serve https   # + a tailscale sidecar; console over TLS on your tailnet
```

Off by default. Read [compose/tailscale/ACL.md](compose/tailscale/ACL.md) first — the ACL has to
be in place before the first start, and an ACL on the wrong port locks you out with a plain
timeout. `--serve` drops the console's `127.0.0.1` port, since the tailnet becomes the access
path; `--local` keeps both.

**The server has no authentication**, so the tailnet ACL is the entire access control and it
authenticates machines, not people. Keep the ACL `src` to your own account, and do not use
`--serve funnel`.

## Test, format, clean

```bash
./vk test              # hermetic: server unit + console. Names the suite it skipped
```

```bash
./vk test integration  # the Testcontainers half; needs a Docker daemon
```

```bash
./vk fmt --check       # prettier over the console; writes nothing, exits 1 on drift
```

```bash
./vk clean             # build artifacts only — it cannot reach a container or a volume
```

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
