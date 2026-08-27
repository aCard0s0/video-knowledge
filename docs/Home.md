# VidIngest Docs

This folder is the project's documentation hub. It focuses on how VidIngest behaves,
how it is configured, and how to operate it locally.

If you're making changes, prefer docs that include **repo-relative code pointers** and
update those pointers when you move/rename code.

## VidIngest

- Overview: [VidIngest](vidingest/VidIngest.md)
- Config and runtime: [VidIngest - Config and Runtime](vidingest/VidIngest%20-%20Config%20and%20Runtime.md)
- Data model: [VidIngest - Data Model](vidingest/VidIngest%20-%20Data%20Model.md)
- Download pipeline: [VidIngest - Download Pipeline](vidingest/VidIngest%20-%20Download%20Pipeline.md)
- Knowledge extraction: [VidIngest - Knowledge Extraction](vidingest/VidIngest%20-%20Knowledge%20Extraction.md)
- Per-phase rerun: [VidIngest - Per-Phase Rerun](vidingest/VidIngest%20-%20Per-Phase%20Rerun.md)
- Web UI: [VidIngest - Web UI](vidingest/VidIngest%20-%20Web%20UI.md)
- YouTube channels: [VidIngest - YouTube Channels](vidingest/VidIngest%20-%20YouTube%20Channels.md)
- CLI commands: [VidIngest - CLI Commands](vidingest/VidIngest%20-%20CLI%20Commands.md)
- MCP with LM Studio: [VidIngest - MCP with LM Studio](vidingest/VidIngest%20-%20MCP%20with%20LM%20Studio.md)
- Test scenarios: [VidIngest - Test Scenarios](vidingest/VidIngest%20-%20Test%20Scenarios.md)

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

- Operator console: <http://localhost:8051/vidingest>
- REST base URL: <http://localhost:8051/vidingest/api/v1>
- MCP (SSE): <http://localhost:8055/vidingest/sse>
- Ports (defaults): postgres 3030, whisper 9000, diarize-asr 9001, paddleocr 8002,
  llm 11434, vidingest 8051, vidingest-mcp 8055
- Health and status checks: `./scripts/tradey.sh status` and `logs`
- Link check: `python3 scripts/check-markdown-links.py`
