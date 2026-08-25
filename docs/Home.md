# VidIngest Docs

This folder is the project's documentation hub. It focuses on how VidIngest behaves,
how it is configured, and how to operate it locally.

If you're making changes, prefer docs that include **repo-relative code pointers** and
update those pointers when you move/rename code.

## VidIngest

- Overview: [VidIngest Console](vidingest/VidIngest%20Console.md)
- Config and runtime: [VidIngest Console - Config and Runtime](vidingest/VidIngest%20Console%20-%20Config%20and%20Runtime.md)
- Data model: [VidIngest Console - Data Model](vidingest/VidIngest%20Console%20-%20Data%20Model.md)
- Download pipeline: [VidIngest Console - Download Pipeline](vidingest/VidIngest%20Console%20-%20Download%20Pipeline.md)
- Knowledge extraction: [VidIngest Console - Knowledge Extraction](vidingest/VidIngest%20Console%20-%20Knowledge%20Extraction.md)
- Per-phase rerun: [VidIngest Console - Per-Phase Rerun](vidingest/VidIngest%20Console%20-%20Per-Phase%20Rerun.md)
- YouTube channels: [VidIngest Console - YouTube Channels](vidingest/VidIngest%20Console%20-%20YouTube%20Channels.md)
- CLI commands: [VidIngest Console - CLI Commands](vidingest/VidIngest%20Console%20-%20CLI%20Commands.md)
- MCP with LM Studio: [VidIngest Console - MCP with LM Studio](vidingest/VidIngest%20Console%20-%20MCP%20with%20LM%20Studio.md)
- Test scenarios: [VidIngest Console - Test Scenarios](vidingest/VidIngest%20Console%20-%20Test%20Scenarios.md)

Diagrams live in [vidingest/diagrams](vidingest/diagrams) (`mermaid/` sources,
`svg/` rendered output — regenerate with `./scripts/regenerate-mermaid-svgs.sh`).

## Local orchestration

Compose is driven by `./scripts/tradey.sh` (see `--help`) which layers `compose.yml`
plus `compose/{infra/infra,services,cli,mcp}.yml`. Host ports come from
[compose/ports.env](../compose/ports.env); everything binds to `127.0.0.1` by default
(`VK_BIND_ADDR`).

## Quick links

- REST base URL: <http://localhost:8051/vidingest>
- MCP (SSE): <http://localhost:8055/vidingest/sse>
- Ports (defaults): timescaledb 3030, whisper 9000, diarize-asr 9001, paddleocr 8002,
  ollama 11434, vidingest 8051, vidingest-mcp 8055
- Health and status checks: `./scripts/tradey.sh status` and `logs`
- Link check: `python3 scripts/check-markdown-links.py`
