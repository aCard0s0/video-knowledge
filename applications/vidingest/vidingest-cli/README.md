# vidingest-cli

A Spring Shell console for `vidingest-server`. It is a **client**, not a second copy of the
pipeline: every command is an HTTP call through `vidingest-client`, so the CLI has no database, no
Liquibase changelog and no yt-dlp or ffmpeg of its own. `web-application-type=none` — it starts a
prompt, not a server.

## Run it

In Docker, against the running stack:

```bash
./scripts/tradey.sh cli
```

On the host, against a server on :8051:

```bash
./mvnw -pl applications/vidingest/vidingest-cli -am clean package
```

```bash
java -jar applications/vidingest/vidingest-cli/target/vidingest-cli-*.jar
```

Build from the repo root with `-am`, not from inside the module: `cd vidingest-cli && mvn package`
resolves the sibling `vidingest-*` jars from `~/.m2` and will happily compile against a stale API.

## Commands

| Command | What it does |
|---|---|
| `ingest` | Download and ingest a video from URL |
| `ingest-file` | Ingest videos from a file (one URL per line) |
| `download` | Download a video from URL |
| `list` | List all ingested videos |
| `status` | Show status of a video by ID |
| `delete` | Delete a video and its associated data |
| `pipelines` | List pipeline runs |
| `retry` | Retry a failed pipeline run by UUID |
| `speakers` | List speakers identified in a video by diarization |
| `knowledge` | List knowledge units for a video |
| `regenerate-knowledge` | Re-run LLM knowledge extraction for a video |
| `search` | Run semantic search over context chunks |
| `search-knowledge` | Semantic search across LLM-extracted knowledge units |
| `vidingest-help` | Show usage, commands and configuration |

Options, arguments and worked examples are in
[VidIngest - CLI Commands](../../../docs/vidingest/VidIngest%20-%20CLI%20Commands.md). That page is
the reference; this table exists only so you know what is there.

## Configuration

Three properties, all pointing at the server — see
[application.properties](src/main/resources/application.properties):

```properties
vidingest.server.base-url=http://localhost:8051/vidingest
vidingest.server.connect-timeout=5s
vidingest.server.read-timeout=10m
```

The long read timeout is deliberate: `ingest` blocks while the server downloads and transcribes.
