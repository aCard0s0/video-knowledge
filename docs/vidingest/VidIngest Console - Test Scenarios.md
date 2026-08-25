# VidIngest Console - Test Scenarios

- **Last reviewed**: 2026-03-15
- **Status**: stable

## Quickstart (for agents)

Test scenarios cover all Spring Shell commands and edge cases. Run tests with:

```bash
./mvnw -pl applications/vidingest/vidingest-server test
./mvnw -pl applications/vidingest/vidingest-cli test
```

**Implementation pointers**

| File | Role |
|------|------|
| `applications/vidingest/vidingest-server/src/test/java/` | Server tests (integration + MCP + pipeline) |
| `applications/vidingest/vidingest-cli/src/test/java/` | CLI tests (command parsing + output formatting) |

## Scenario categories

### Download scenarios

| ID | Scenario | Input | Expected outcome |
|----|----------|-------|-----------------|
| DL-01 | Download YouTube video to database | `download --url https://www.youtube.com/watch?v=VIDEO_ID` | Video entity created, file on disk, output shows ID/title/path |
| DL-02 | Download YouTube video to disk only | `download --url https://...VIDEO_ID --disk-only true` | File at `{channelName}/YYYYMMDD.title.mp4`, metadata JSON alongside, no DB record |
| DL-02b | Download with live progress | `download --url https://...VIDEO_ID --progress true` | Real-time yt-dlp progress appears in shell, command still returns final summary |
| DL-03 | Download with invalid URL | `download --url http://invalid-url` | Error message: `ERROR [Download]: ...` |
| DL-04 | Download when yt-dlp not installed | `download --url ...` with yt-dlp missing | Error message mentioning yt-dlp execution failure |
| DL-05 | Download private/unavailable video | `download --url ...` for a private video | Error from yt-dlp with descriptive message |
| DL-06 | Download timeout | Set `vidingest.download.timeout-seconds=1` and run a long URL | Error message includes `yt-dlp timed out after 1 seconds` |

### Ingest scenarios

| ID | Scenario | Input | Expected outcome |
|----|----------|-------|-----------------|
| IN-01 | Ingest a YouTube video | `ingest --url https://...VIDEO_ID` | IngestionJob created (COMPLETED), Video entity (COMPLETED), file on disk |
| IN-02 | Ingest duplicate video | `ingest --url ...` for already-ingested URL | Error: `Video already ingested: youtube/VIDEO_ID` |
| IN-03 | Ingest with skip-transcription | `ingest --url ... --skip-transcription true` | Output includes "(Transcription skipped)" |
| IN-04 | Dry run | `ingest --url ... --dry-run true` | No download, no DB write, output: "Dry run successful." |
| IN-05 | Ingest Vimeo video | `ingest --url https://vimeo.com/VIDEO_ID` | Source set to `vimeo`, metadata extracted correctly |

### Batch ingest scenarios

| ID | Scenario | Input | Expected outcome |
|----|----------|-------|-----------------|
| BA-01 | Batch ingest from file | `ingest-file --file urls.txt` | All valid URLs processed, summary shows correct counts |
| BA-02 | Batch with comments and blanks | File with `# comment` lines and blank lines | Comments/blanks skipped, total count reflects only valid URLs |
| BA-03 | Batch with some failures | File with mix of valid and invalid URLs | Partial success: `X total, Y successful, Z failed` |
| BA-04 | Batch with nonexistent file | `ingest-file --file nonexistent.txt` | Error: `ERROR [File read]: ...` |
| BA-05 | Batch with duplicates | File containing the same URL twice | First succeeds, second fails as duplicate |

### Query scenarios

| ID | Scenario | Input | Expected outcome |
|----|----------|-------|-----------------|
| QR-01 | Status of existing video | `status --video-id <UUID>` | All fields displayed: ID, title, source, status, file, created |
| QR-02 | Status of nonexistent video | `status --video-id <random-UUID>` | Error: `ERROR [Status]: Video not found` |
| QR-03 | Status with invalid UUID | `status --video-id not-a-uuid` | Error about invalid UUID format |
| QR-04 | List with videos present | `list` after ingesting videos | Table with ID, truncated title, status |
| QR-05 | List with empty database | `list` on fresh database | "No videos found" |
| QR-06 | Semantic search disabled | `search --query "support zone"` with default config | Error: semantic search disabled with remediation hint |
| QR-07 | Retry failed pipeline run | `retry --pipeline-id <FAILED_PIPELINE_UUID>` | Existing failed pipeline run is retried and reaches `COMPLETED` on success |

### Configuration scenarios

| ID | Scenario | Setup | Expected outcome |
|----|----------|-------|-----------------|
| CF-01 | Custom storage path | Set `vidingest.storage.video-path=/custom/path` | Downloads go to `/custom/path` |
| CF-02 | Environment variable override | Set `DB_HOST=custom-host` | Connects to `custom-host` instead of `localhost` |
| CF-03 | Docker profile | Run with `SPRING_PROFILES_ACTIVE=docker` | Uses Docker networking and container paths |
| CF-04 | Dev profile | Run with `--spring.profiles.active=dev` | Verbose SQL logging visible |

### Error handling scenarios

| ID | Scenario | Trigger | Expected outcome |
|----|----------|---------|-----------------|
| ER-01 | Database unreachable | Stop PostgreSQL before starting app | Startup failure with connection error |
| ER-02 | yt-dlp network timeout | Disconnect network during download | `IOException` with yt-dlp error output |
| ER-03 | Disk full | Fill disk during download | `IOException` from file write |
| ER-04 | Invalid metadata JSON | yt-dlp returns malformed output | Error: "Metadata extraction failed: Invalid JSON response" |

## Unit test targets

These classes should have dedicated unit tests:

| Class | Test focus |
|-------|-----------|
| `MetadataExtractor` | Field extraction from various yt-dlp JSON shapes, null handling, date parsing |
| `FileSystemHelper` | Filename sanitization, directory creation, file discovery |
| `YtDlpCommandBuilder` | Command line construction with various config combinations |
| `MetadataService` | Entity creation/update from metadata maps |

## Integration test targets

| Scope | Test focus |
|-------|-----------|
| `IngestionOrchestratorIntegrationTest` | Pipeline with Testcontainers PostgreSQL, duplicate handling, retry flow, semantic search |
| `MetadataServiceIntegrationTest` | Metadata create/update behavior against PostgreSQL |
| `RuntimeCoexistenceSmokeTest` | Spring Shell + MVC + MCP transport beans and endpoint wiring |
| Liquibase migrations | Schema creation against clean PostgreSQL container |

## Related pages

- [VidIngest Console](VidIngest%20Console.md)
- [VidIngest Console - CLI Commands](VidIngest%20Console%20-%20CLI%20Commands.md)
