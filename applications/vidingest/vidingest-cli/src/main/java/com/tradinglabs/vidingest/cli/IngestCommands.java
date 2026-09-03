package com.tradinglabs.vidingest.cli;

import com.tradinglabs.vidingest.api.common.PageResponse;
import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitDto;
import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import com.tradinglabs.vidingest.api.knowledge.SearchKnowledgeHit;
import com.tradinglabs.vidingest.api.pipeline.CreatePipelineRunRequest;
import com.tradinglabs.vidingest.api.pipeline.CreatePipelineRunResponse;
import com.tradinglabs.vidingest.api.pipeline.RunSummary;
import com.tradinglabs.vidingest.api.pipeline.RetryRunRequest;
import com.tradinglabs.vidingest.api.search.SearchChunkResult;
import com.tradinglabs.vidingest.api.speakers.SpeakerDto;
import com.tradinglabs.vidingest.api.videos.DeleteVideoResult;
import com.tradinglabs.vidingest.api.videos.DownloadToDiskResult;
import com.tradinglabs.vidingest.api.videos.DownloadVideoResponse;
import com.tradinglabs.vidingest.api.videos.VideoSummary;
import com.tradinglabs.vidingest.api.videos.RunVideoPhaseResult;
import com.tradinglabs.vidingest.client.VidingestClient;
import com.tradinglabs.vidingest.client.VidingestClientException;
import com.tradinglabs.vidingest.client.VidingestClientProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Spring Shell commands for VidIngest CLI (remote-only).
 * All operations are executed by calling a running {@code vidingest-server}.
 */
@ShellComponent
@RequiredArgsConstructor
@Slf4j
public class IngestCommands {

    private static final String SKIP_PHASES_HELP =
            "Comma-separated optional phases to skip: TRANSCRIBE, DIARIZE, FRAME_SAMPLE, OCR, "
            + "FUSE, KNOWLEDGE, CONTEXT. Pass an empty string to run every enabled phase.";

    /**
     * The enrichment phases stay opt-in from the CLI, matching what the six boolean flags
     * defaulted to before they collapsed into one option.
     */
    private static final String DEFAULT_SKIP_PHASES = "DIARIZE,FRAME_SAMPLE,OCR,KNOWLEDGE";

    /**
     * Retry has no default of its own: omitting the option reuses the phase set the run was created
     * with. A fixed default here would have retried every run with the same opinionated list,
     * whatever the run itself was configured to do.
     */
    private static final String RETRY_SKIP_PHASES_HELP =
            "Comma-separated optional phases to skip. Omit to retry with the phases the run itself "
            + "was created with; pass an empty string to run every enabled phase.";

    private final VidingestClient client;
    private final VidingestClientProperties properties;

    private static Set<String> parseSkipPhases(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @ShellMethod(key = "ingest", value = "Download and ingest a video from URL")
    public String ingest(
            @ShellOption(help = "Video URL") String url,
            @ShellOption(help = "Config file path", defaultValue = ShellOption.NULL) String config,
            @ShellOption(help = SKIP_PHASES_HELP, defaultValue = DEFAULT_SKIP_PHASES) String skipPhases,
            @ShellOption(help = "Dry run (validate only)", defaultValue = "false") boolean dryRun) {

        try {
            String validationError = validateUrl(url);
            if (validationError != null) {
                return validationError;
            }

            if (dryRun) {
                log.info("DRY RUN: Would ingest video from: {}", url);
                return "Dry run successful. Video URL is valid.";
            }

            log.info("Starting ingestion pipeline for: {}", url);

            Set<String> skipped = parseSkipPhases(skipPhases);
            CreatePipelineRunResponse response = client.createPipelineRun(
                    new CreatePipelineRunRequest(List.of(url), skipped));
            if (response.items() == null || response.items().isEmpty()) {
                return "ERROR [Ingest]: Server returned empty pipeline response";
            }

            long accepted = response.items().stream()
                    .filter(i -> i != null && i.status() == CreatePipelineRunResponse.ItemStatus.ACCEPTED)
                    .count();
            long rejected = response.items().stream()
                    .filter(i -> i != null && i.status() == CreatePipelineRunResponse.ItemStatus.REJECTED)
                    .count();

            StringBuilder sb = new StringBuilder();
            sb.append("Ingestion accepted.\n");
            if (response.runId() != null && !response.runId().isBlank()) {
                sb.append("Pipeline run ID: ").append(response.runId()).append("\n");
            }
            sb.append("Items: ").append(accepted).append(" accepted, ").append(rejected).append(" rejected\n");
            if (!skipped.isEmpty()) {
                sb.append("(Skipped: ").append(String.join(", ", skipped)).append(")\n");
            }
            sb.append("Tip: use `pipelines` to watch progress, and `retry` for failed runs.");
            return sb.toString();

        } catch (Exception e) {
            log.error("Ingestion failed", e);
            return formatError("Ingestion", e);
        }
    }

    @ShellMethod(key = "ingest-file", value = "Ingest videos from a file (one URL per line)")
    public String ingestFile(
            @ShellOption(help = "File containing video URLs") String file,
            @ShellOption(help = "Config file path", defaultValue = ShellOption.NULL) String config) {

        try {
            List<String> lines = Files.readAllLines(Paths.get(file));

            List<String> urls = lines.stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();

            if (urls.isEmpty()) {
                return "No URLs found in file";
            }

            List<String> validUrls = urls.stream()
                    .map(String::trim)
                    .filter(u -> validateUrl(u) == null)
                    .toList();

            if (validUrls.isEmpty()) {
                return "No valid URLs found in file";
            }

            log.info("Creating one pipeline run for {} URLs from file: {}", validUrls.size(), file);
            CreatePipelineRunResponse response = client.createPipelineRun(new CreatePipelineRunRequest(
                    validUrls,
                    parseSkipPhases(DEFAULT_SKIP_PHASES)
            ));

            long accepted = response.items() != null
                    ? response.items().stream().filter(i -> i != null && i.status() == CreatePipelineRunResponse.ItemStatus.ACCEPTED).count()
                    : 0;
            long rejected = response.items() != null
                    ? response.items().stream().filter(i -> i != null && i.status() == CreatePipelineRunResponse.ItemStatus.REJECTED).count()
                    : 0;

            StringBuilder sb = new StringBuilder();
            sb.append("Batch ingestion accepted.\n");
            if (response.runId() != null && !response.runId().isBlank()) {
                sb.append("Pipeline run ID: ").append(response.runId()).append("\n");
            }
            sb.append("Items: ").append(accepted).append(" accepted, ").append(rejected).append(" rejected\n");
            sb.append("Tip: use `pipelines` to watch progress.");
            return sb.toString();

        } catch (IOException e) {
            log.error("Failed to read file", e);
            return formatError("File read", e);
        }
    }

    @ShellMethod(key = "download", value = "Download a video from URL")
    public String download(
            @ShellOption(help = "Video URL") String url,
            @ShellOption(help = "Save to disk only with channel folder structure (does not save to database)", defaultValue = "false") boolean diskOnly,
            @ShellOption(help = "Stream real-time yt-dlp progress output", defaultValue = "false") boolean progress) {
        try {
            String validationError = validateUrl(url);
            if (validationError != null) {
                return validationError;
            }

            if (progress) {
                log.info("Progress streaming requested; server-side progress will be available in vidingest-server logs.");
            }

            DownloadVideoResponse response = client.downloadVideo(url, diskOnly, progress);

            if (diskOnly) {
                DownloadToDiskResult paths = response.downloadToDisk();
                if (paths == null) {
                    return "ERROR [Download]: Server returned empty disk download result";
                }
                StringBuilder result = new StringBuilder("Download complete (disk only):\n");
                result.append("Video: ").append(paths.videoPath()).append("\n");
                if (paths.metadataPath() != null && !paths.metadataPath().isBlank()) {
                    result.append("Metadata: ").append(paths.metadataPath());
                }
                return result.toString();
            }

            VideoSummary video = response.video();
            if (video == null) {
                return "ERROR [Download]: Server returned empty download result";
            }

            return String.format(
                    "Download complete:\nVideo ID: %s\nTitle: %s\nFile: %s",
                    video.id(), video.title(), video.filePath()
            );

        } catch (Exception e) {
            log.error("Download failed", e);
            return formatError("Download", e);
        }
    }

    @ShellMethod(key = "status", value = "Show status of a video by ID")
    public String status(@ShellOption(help = "Video UUID") String videoId) {
        try {
            UUID uuid = UUID.fromString(videoId);
            VideoSummary video = client.getVideo(uuid);

            return String.format(
                    "Video: %s\nTitle: %s\nSource: %s\nStatus: %s\nFile: %s\nCreated: %s",
                    video.id(),
                    video.title(),
                    video.source(),
                    video.status(),
                    video.filePath(),
                    video.createdAt()
            );

        } catch (Exception e) {
            log.error("Failed to get status", e);
            return formatError("Status", e);
        }
    }

    @ShellMethod(key = "list", value = "List all ingested videos")
    public String list() {
        PageResponse<VideoSummary> page = client.listVideos();
        List<VideoSummary> videos = page.items();

        if (videos.isEmpty()) {
            return "No videos found";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Found %d videos:\n\n", videos.size()));

        for (VideoSummary video : videos) {
            String title = video.title();
            String displayTitle = title != null
                    ? title.substring(0, Math.min(50, title.length()))
                    : "N/A";
            sb.append(String.format(
                    "ID: %s | Title: %s | Status: %s\n",
                    video.id(), displayTitle, video.status()
            ));
        }

        return sb.toString();
    }

    @ShellMethod(key = "vidingest-help", value = "Show VidIngest usage, commands, and configuration")
    public String vidingestHelp() {
        StringBuilder sb = new StringBuilder();
        sb.append("VidIngest CLI (remote-only) - Video-to-Knowledge Ingestion Pipeline\n");
        sb.append("=========================================================\n\n");

        sb.append("Commands:\n");
        sb.append("  ingest --url <URL>                 Download, extract metadata, and persist a video\n");
        sb.append("  ingest-file --file <PATH>          Batch ingest from a text file (one URL per line)\n");
        sb.append("  download --url <URL>                Download a video (use --disk-only for no DB)\n");
        sb.append("  status --video-id <UUID>            Show status of a video\n");
        sb.append("  list                                List all ingested videos\n");
        sb.append("  pipelines [--status <STATUS>]       List pipeline runs\n");
        sb.append("  search --query <TEXT>               Semantic search across context chunks\n");
        sb.append("  retry --pipeline-id <UUID>          Retry a failed pipeline run\n");
        sb.append("  delete --video-id <UUID>            Delete a video and its data\n");
        sb.append("  search-knowledge --query <TEXT>     Semantic search across knowledge units (M8)\n");
        sb.append("  knowledge --video-id <UUID>         List knowledge units for a video (M8)\n");
        sb.append("  regenerate-knowledge --video-id <UUID>  Re-run LLM knowledge extraction (M8)\n");
        sb.append("  speakers --video-id <UUID>          List speakers identified by diarization (M8)\n");
        sb.append("  vidingest-help                      Show this help\n");
        sb.append("  help                                Show all Spring Shell commands\n\n");

        sb.append("Configuration:\n");
        sb.append("  Server base URL:  ").append(properties.baseUrl()).append("\n");
        sb.append("  Connect timeout:  ").append(properties.connectTimeout()).append("\n");
        sb.append("  Read timeout:     ").append(properties.readTimeout()).append("\n\n");

        sb.append("Examples:\n");
        sb.append("  shell:> ingest --url https://www.youtube.com/watch?v=VIDEO_ID\n");
        sb.append("  shell:> download --url https://www.youtube.com/watch?v=VIDEO_ID --disk-only true\n");
        sb.append("  shell:> download --url https://www.youtube.com/watch?v=VIDEO_ID --progress true\n");
        sb.append("  shell:> ingest-file --file /path/to/urls.txt\n");
        sb.append("  shell:> pipelines --status FAILED\n");
        sb.append("  shell:> retry --pipeline-id 123e4567-e89b-12d3-a456-426614174000\n");

        return sb.toString();
    }

    @ShellMethod(key = "pipelines", value = "List pipeline runs")
    public String pipelines(
            @ShellOption(help = "Filter by status (PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED, or ALL)",
                         defaultValue = "ALL") String status) {
        try {
            PageResponse<RunSummary> page = client.listPipelines(status);
            List<RunSummary> runs = page.items();

            if (runs.isEmpty()) {
                return "No pipeline runs found" + (!"ALL".equalsIgnoreCase(status) ? " with status " + status : "");
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Found %d pipeline runs:\n\n", runs.size()));

            for (RunSummary run : runs) {
                sb.append(String.format("ID: %s | Status: %-11s | Phase: %-10s | Created: %s",
                        run.id(), run.status(), run.phase(), run.createdAt()));
                if (run.error() != null && !run.error().isBlank()) {
                    String errorPreview = run.error().length() > 60
                            ? run.error().substring(0, 60) + "..."
                            : run.error();
                    sb.append(" | Error: ").append(errorPreview);
                }
                sb.append("\n");
            }

            return sb.toString();

        } catch (IllegalArgumentException e) {
            return "ERROR [Pipelines]: Invalid status. Use: PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED, or ALL";
        }
    }

    @ShellMethod(key = "search", value = "Run semantic search over context chunks")
    public String search(
            @ShellOption(help = "Natural language query") String query,
            @ShellOption(help = "Maximum number of results", defaultValue = "5") int limit) {
        try {
            List<SearchChunkResult> results = client.search(query, limit);
            if (results.isEmpty()) {
                return "No matching chunks found";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Found %d chunk matches:\n\n", results.size()));
            for (SearchChunkResult result : results) {
                sb.append("Chunk ID: ").append(result.chunkId()).append("\n");
                sb.append("Video ID: ").append(result.videoId()).append("\n");
                sb.append("Chunk: ").append(result.chunkIndex()).append("\n");
                sb.append("Title: ").append(orDefault(result.videoTitle(), "N/A")).append("\n");
                sb.append("Channel: ").append(orDefault(result.channelName(), "N/A")).append("\n");
                sb.append("File: ").append(orDefault(result.filePath(), "N/A")).append("\n");
                sb.append("Snippet: ").append(orDefault(result.snippet(), "")).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("Search failed", e);
            return formatError("Search", e);
        }
    }

    @ShellMethod(key = "retry", value = "Retry a failed pipeline run by UUID")
    public String retry(
            @ShellOption(help = "Pipeline UUID") String pipelineId,
            @ShellOption(help = RETRY_SKIP_PHASES_HELP, defaultValue = ShellOption.NULL) String skipPhases) {
        try {
            UUID uuid = UUID.fromString(pipelineId);
            // null, not an empty set: the server reads the run's own set when the field is absent.
            CreatePipelineRunResponse result = client.retryPipeline(uuid, new RetryRunRequest(
                    skipPhases == null ? null : parseSkipPhases(skipPhases)));
            if (result.items() == null || result.items().isEmpty()) {
                return "ERROR [Retry]: Server returned empty retry response";
            }
            long accepted = result.items().stream()
                    .filter(i -> i != null && i.status() == CreatePipelineRunResponse.ItemStatus.ACCEPTED)
                    .count();
            long rejected = result.items().stream()
                    .filter(i -> i != null && i.status() == CreatePipelineRunResponse.ItemStatus.REJECTED)
                    .count();

            StringBuilder sb = new StringBuilder();
            sb.append("Retry accepted.\n");
            sb.append("Pipeline run ID: ").append(result.runId()).append("\n");
            sb.append("Items: ").append(accepted).append(" accepted, ").append(rejected).append(" rejected");
            return sb.toString();
        } catch (Exception e) {
            log.error("Retry failed", e);
            return formatError("Retry", e);
        }
    }

    @ShellMethod(key = "delete", value = "Delete a video and its associated data")
    public String delete(
            @ShellOption(help = "Video UUID") String videoId,
            @ShellOption(help = "Skip confirmation", defaultValue = "false") boolean force) {
        try {
            UUID uuid = UUID.fromString(videoId);

            if (!force) {
                VideoSummary video = client.getVideo(uuid);
                return String.format(
                        "About to delete video: %s (%s)\nRun again with --force true to confirm.",
                        video.title(), video.id());
            }

            DeleteVideoResult result = client.deleteVideo(uuid);
            return "Video " + result.videoId() + " deleted successfully";

        } catch (Exception e) {
            log.error("Delete failed", e);
            return formatError("Delete", e);
        }
    }

    // ---------------------------------------------------------------------------
    // M8 — knowledge / speakers commands
    // ---------------------------------------------------------------------------

    @ShellMethod(key = "search-knowledge", value = "Semantic search across LLM-extracted knowledge units")
    public String searchKnowledge(
            @ShellOption(help = "Natural language query") String query,
            @ShellOption(help = "Filter by type (PROCEDURE, ENTITY, TOPIC, SUMMARY, CLAIM, QUESTION). Blank = all.",
                    defaultValue = "") String type,
            @ShellOption(help = "Maximum number of results", defaultValue = "10") int limit) {
        try {
            KnowledgeUnitType parsed = parseTypeOrNull(type);
            List<SearchKnowledgeHit> hits = client.searchKnowledge(query, parsed, limit);
            if (hits.isEmpty()) {
                return "No matching knowledge units found";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Found %d knowledge units:%n%n", hits.size()));
            for (SearchKnowledgeHit hit : hits) {
                sb.append("Unit ID:  ").append(hit.knowledgeUnitId()).append('\n');
                sb.append("Type:     ").append(hit.type()).append('\n');
                sb.append("Title:    ").append(orDefault(hit.title(), "N/A")).append('\n');
                sb.append("Video:    ").append(orDefault(hit.videoTitle(), "N/A"))
                        .append(" (").append(hit.videoId()).append(")\n");
                if (hit.channelName() != null) sb.append("Channel:  ").append(hit.channelName()).append('\n');
                sb.append("Snippet:  ").append(orDefault(hit.snippet(), "")).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("search-knowledge failed", e);
            return formatError("SearchKnowledge", e);
        }
    }

    @ShellMethod(key = "knowledge", value = "List knowledge units for a video")
    public String knowledge(
            @ShellOption(help = "Video UUID") String videoId,
            @ShellOption(help = "Filter by type (PROCEDURE, ENTITY, TOPIC, SUMMARY, CLAIM, QUESTION). Blank = all.",
                    defaultValue = "") String type) {
        try {
            UUID uuid = UUID.fromString(videoId);
            KnowledgeUnitType parsed = parseTypeOrNull(type);
            List<KnowledgeUnitDto> units = client.getKnowledgeUnits(uuid, parsed);
            if (units.isEmpty()) {
                return "No knowledge units found for video " + uuid;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Found %d knowledge units:%n%n", units.size()));
            for (KnowledgeUnitDto u : units) {
                sb.append(String.format("[%s] %s%n", u.type(), orDefault(u.title(), "(no title)")));
                String content = u.content();
                if (content != null && content.length() > 220) {
                    content = content.substring(0, 220) + "...";
                }
                sb.append("  ").append(orDefault(content, "")).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("knowledge failed", e);
            return formatError("Knowledge", e);
        }
    }

    @ShellMethod(key = "regenerate-knowledge", value = "Re-run LLM knowledge extraction for a video")
    public String regenerateKnowledge(@ShellOption(help = "Video UUID") String videoId) {
        try {
            UUID uuid = UUID.fromString(videoId);
            RunVideoPhaseResult result = client.runVideoPhase(uuid, "KNOWLEDGE");
            return String.format("Knowledge regenerated for %s: %s units persisted in %dms",
                    result.videoId(), result.rowsAffected(), result.elapsedMs());
        } catch (Exception e) {
            log.error("regenerate-knowledge failed", e);
            return formatError("RegenerateKnowledge", e);
        }
    }

    @ShellMethod(key = "speakers", value = "List speakers identified in a video by diarization")
    public String speakers(@ShellOption(help = "Video UUID") String videoId) {
        try {
            UUID uuid = UUID.fromString(videoId);
            List<SpeakerDto> speakers = client.getSpeakers(uuid);
            if (speakers.isEmpty()) {
                return "No speakers found for video " + uuid + " (did diarization run?)";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Found %d speakers:%n%n", speakers.size()));
            for (SpeakerDto s : speakers) {
                String name = s.displayName() != null && !s.displayName().isBlank()
                        ? s.displayName() : s.label();
                sb.append(String.format("ID: %s | %-30s | %d segments%n",
                        s.id(), name, s.segmentCount()));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("speakers failed", e);
            return formatError("Speakers", e);
        }
    }

    private static KnowledgeUnitType parseTypeOrNull(String type) {
        if (type == null || type.isBlank()) return null;
        try {
            return KnowledgeUnitType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String formatError(String operation, Exception e) {
        if (e instanceof VidingestClientException ce) {
            String status = ce.getStatusCode() != null ? Integer.toString(ce.getStatusCode().value()) : "n/a";
            return String.format("ERROR [%s]: %s (status=%s)", operation, ce.getMessage(), status);
        }
        return String.format("ERROR [%s]: %s", operation, e.getMessage());
    }

    private String orDefault(String value, String fallback) {
        return value != null && !value.isEmpty() ? value : fallback;
    }

    private String validateUrl(String url) {
        if (url == null || url.isBlank()) {
            return "ERROR [Validation]: URL cannot be empty";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "ERROR [Validation]: URL must start with http:// or https://";
        }
        return null;
    }
}
