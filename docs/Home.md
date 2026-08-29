# VidIngest Docs

This folder is the project's documentation hub. It focuses on how VidIngest behaves,
how it is configured, and how to operate it locally.

If you're making changes, prefer docs that include **repo-relative code pointers** and
update those pointers when you move/rename code.

## VidIngest

How to read this shelf, and what its frontmatter means: [vidingest/CONTEXT.md](vidingest/CONTEXT.md).

- Overview: [VidIngest](vidingest/VidIngest.md)
- Config and runtime: [VidIngest - Config and Runtime](vidingest/VidIngest%20-%20Config%20and%20Runtime.md)
- Data model: [VidIngest - Data Model](vidingest/VidIngest%20-%20Data%20Model.md)
- Download pipeline: [VidIngest - Download Pipeline](vidingest/VidIngest%20-%20Download%20Pipeline.md)
- Knowledge extraction: [VidIngest - Knowledge Extraction](vidingest/VidIngest%20-%20Knowledge%20Extraction.md)
- Per-phase rerun: [VidIngest - Per-Phase Rerun](vidingest/VidIngest%20-%20Per-Phase%20Rerun.md)
- Web UI: [VidIngest - Web UI](vidingest/VidIngest%20-%20Web%20UI.md)
  - measured API behaviour: [VidIngest - Web UI API Findings](vidingest/VidIngest%20-%20Web%20UI%20API%20Findings.md)
  - how the app is built: [VidIngest - Web UI App Guide](vidingest/VidIngest%20-%20Web%20UI%20App%20Guide.md)
- YouTube channels: [VidIngest - YouTube Channels](vidingest/VidIngest%20-%20YouTube%20Channels.md)
- CLI commands: [VidIngest - CLI Commands](vidingest/VidIngest%20-%20CLI%20Commands.md)
- MCP with LM Studio: [VidIngest - MCP with LM Studio](vidingest/VidIngest%20-%20MCP%20with%20LM%20Studio.md)
- Test scenarios: [VidIngest - Test Scenarios](vidingest/VidIngest%20-%20Test%20Scenarios.md)

## Change-impact map

- What a change hits: [map/](map/CLAUDE.md) — nouns, the five processes, and
  [effects](map/effects/CONTEXT.md). Cites `path:line`; the code stays the source of truth.

## Frontend

- Skill order for UI work: [frontend-skills.md](frontend-skills.md)
- How the console was first generated: [frontend-bootstrap-prompt.md](frontend-bootstrap-prompt.md)
  — historical. The console exists; this is kept as provenance, like
  [design-system/vidingest-console/MASTER.md](../design-system/vidingest-console/MASTER.md).

Diagrams live in [vidingest/diagrams](vidingest/diagrams) (`mermaid/` sources,
`svg/` rendered output — regenerate with `./scripts/regenerate-mermaid-svgs.sh`).

## Local orchestration

Compose is driven by `./scripts/tradey.sh` (see `--help`) which layers `compose.yml`
plus `compose/{infra/infra,services,cli,mcp}.yml`. Host ports come from
[compose/ports.env](../compose/ports.env); everything binds to `127.0.0.1` by default
(`VK_BIND_ADDR`).

## Quick links

- Operator console: <http://localhost:8052/vidingest> (the `webapp` nginx container; it proxies the
  API, so it is same-origin with it)
- REST base URL: <http://localhost:8051/vidingest/api/v1>
- MCP (SSE): <http://localhost:8055/vidingest/sse>
- Ports (defaults): postgres 3030, diarize-asr 9001, paddleocr 8002, vidingest 8051, webapp 8052,
  vidingest-mcp 8055. The model runtime is **not** a container — `VK_HOST_LLM_URL`, default
  `http://host.docker.internal:8000/v1` (oMLX on the host)
- Health and status checks: `./scripts/tradey.sh status` and `logs`
- Link check: `python3 scripts/check-markdown-links.py`
