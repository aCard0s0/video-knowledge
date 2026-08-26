# VidIngest - Video-to-Knowledge Ingestion Pipeline

A Spring Boot CLI application for downloading videos, extracting metadata, transcribing audio, and generating AI-ready context with embeddings.

## Quick Start

### 1. Java (required)

This module targets **Java 26** (see `applications/vidingest/pom.xml`).

If you use SDKMAN:

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk use java 26.0.1-tem
mvn -version  # should show Java 26.x
```

### 2. Prerequisites

```bash
# Install yt-dlp
brew install yt-dlp  # macOS
# or
pip install yt-dlp   # Linux/Windows

# Verify
yt-dlp --version
```

### 3. Setup Database (PostgreSQL)

This project’s default local config (`src/main/resources/application.properties`) points to:

- **DB**: `tradingPlatformDB`
- **User**: `dealer`
- **Pass**: `dev_dealer`
- **Host/Port**: `localhost:3030` (matches the `compose/infra/infra.yml` mapping `3030:5432`)

If you’re using the bundled Docker compose, just start TimescaleDB from the repo root:

```bash
./scripts/tradey.sh start db
```

### 4. Build and Run

```bash
# Build and run VidIngest CLI
cd applications/vidingest/vidingest-cli
../../../mvnw clean package -DskipTests
../../../mvnw spring-boot:run
```

**Note**: The application runs as a terminal-based CLI (no web server). Wait for the `shell:>` prompt to appear.

### 5. Use CLI Commands

```bash
# Ingest a video
ingest --url "https://www.youtube.com/watch?v=VIDEO_ID"

# List all videos
list

# Check status
status --video-id <uuid>

# Batch ingest
ingest-file --file videos.txt

# Exit
exit
```

## Features

- ✅ Download videos from YouTube (and other yt-dlp supported sources)
- ✅ Extract and store metadata
- ✅ PostgreSQL storage with pgvector support
- ✅ Spring Shell interactive CLI
- ✅ Pipeline run tracking and status monitoring
- 🚧 Transcription with Whisper (TODO)
- 🚧 Context chunking and embeddings (TODO)

## Documentation

- **Liquibase**: migrations live in `src/main/resources/db/changelog/` and should create `databasechangelog` / `databasechangeloglock` on first successful start.

## Configuration

Edit `src/main/resources/application.properties` or create a custom YAML config:

```properties
# Database
spring.datasource.url=jdbc:postgresql://localhost:3030/tradingPlatformDB
spring.datasource.username=dealer
spring.datasource.password=dev_dealer

# Storage
vidingest.storage.video-path=/data/videos
vidingest.storage.temp-path=/tmp/vidingest

# Download
vidingest.download.tool=yt-dlp
vidingest.download.format=bestvideo+bestaudio
vidingest.download.retries=3
```

## Project Structure (Feature-First)

```
vid-ingest/
├── cli/              # Spring Shell commands
├── config/           # Configuration management
├── shared/           # Shared domain entities and repos
│   ├── domain/       # Video, VideoStatus, RunStatus
│   └── repo/         # VideoRepository
├── download/         # Video download feature
├── metadata/         # Metadata extraction feature
├── transcription/    # Transcription feature
├── context/          # Context/embedding feature
└── ingestion/        # Pipeline orchestration
```

Each feature contains its own `domain/`, `service/`, and `repo/` packages following the feature-first pattern used across the reactor.

## Integration

Part of the TradingLabs Platform:
- **Port**: 8051
- **Context Path**: `/vidingest`
- **Database**: PostgreSQL with pgvector

## Next Steps

1. Implement `TranscriptionService` with Whisper integration
2. Implement `ContextService` for chunking and embeddings
3. Add comprehensive error handling and retry logic
4. Write tests
5. Create REST API layer (optional)

## License

Part of TradingLabs Platform
