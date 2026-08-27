package com.tradinglabs.vidingest.api.paths;

/**
 * Shared URL path constants for the VidIngest REST API.
 *
 * <p>These constants are intentionally relative to the service base URL (including servlet context-path).
 */
public final class VidIngestApiPaths {

    public static final String API_V1 = "/api/v1";

    public static final String VIDEOS = API_V1 + "/videos";
    public static final String PIPELINES = API_V1 + "/pipelines";
    public static final String SEARCH = API_V1 + "/search";
    public static final String HEALTH = API_V1 + "/health";
    public static final String HEALTH_READY = HEALTH + "/ready";
    public static final String HEALTH_LLM = HEALTH + "/llm";
    public static final String YOUTUBE = API_V1 + "/youtube";
    public static final String YOUTUBE_CHANNELS = YOUTUBE + "/channels";

    public static final String VIDEOS_DOWNLOAD = VIDEOS + "/download";

    public static final String VIDEO = VIDEOS + "/{videoId}";
    public static final String VIDEO_FILE = VIDEO + "/file";
    public static final String VIDEO_TRANSCRIPTION = VIDEO + "/transcription";
    public static final String VIDEO_TRANSCRIPTION_SEGMENTS = VIDEO_TRANSCRIPTION + "/segments";
    public static final String VIDEO_DETAIL = VIDEO + "/detail";
    public static final String VIDEO_TRANSCRIPTION_WHISPER_TXT = VIDEO_TRANSCRIPTION + "/whisper.txt";
    public static final String VIDEO_TRANSCRIPTION_WHISPER_JSON = VIDEO_TRANSCRIPTION + "/whisper.json";
    public static final String VIDEO_CONTEXT_REGENERATE = VIDEO + "/context/regenerate";

    public static final String PIPELINE_CAPABILITIES = PIPELINES + "/capabilities";

    public static final String PIPELINE = PIPELINES + "/{runId}";
    public static final String PIPELINE_RETRY = PIPELINE + "/retry";
    public static final String PIPELINE_ITEM_RETRY = PIPELINE + "/items/{itemId}/retry";
    public static final String PIPELINE_AUDIT = PIPELINE + "/audit";
    public static final String PIPELINE_ITEM_AUDIT = PIPELINE + "/items/{itemId}/audit";

    public static final String AUDIT = API_V1 + "/audit";
    public static final String AUDIT_EVENTS = AUDIT + "/events";

    // --- Multi-modal knowledge extraction (M1 stubs; endpoints implemented in later milestones) ---
    public static final String KNOWLEDGE = API_V1 + "/knowledge";
    public static final String KNOWLEDGE_SEARCH = KNOWLEDGE + "/search";

    public static final String VIDEO_KNOWLEDGE = VIDEO + "/knowledge";
    public static final String VIDEO_KNOWLEDGE_REGENERATE = VIDEO_KNOWLEDGE + "/regenerate";
    public static final String VIDEO_SPEAKERS = VIDEO + "/speakers";
    public static final String VIDEO_OCR = VIDEO + "/ocr";
    public static final String VIDEO_OCR_FRAMES = VIDEO_OCR + "/frames";
    public static final String VIDEO_MULTIMODAL_TIMELINE = VIDEO + "/multimodal-timeline";
    public static final String VIDEO_MULTIMODAL_TIMELINE_PAGE = VIDEO_MULTIMODAL_TIMELINE + "/page";

    public static final String SPEAKERS = API_V1 + "/speakers";
    public static final String SPEAKER = SPEAKERS + "/{speakerId}";

    // --- Frame artifacts ---
    public static final String FRAMES = API_V1 + "/frames";
    public static final String FRAME_IMAGE = FRAMES + "/{frameId}/image";

    // --- Per-phase video rerun ---
    public static final String VIDEO_PHASES = VIDEO + "/phases";
    public static final String VIDEO_PHASE_RUN = VIDEO_PHASES + "/{phase}/run";

    public static final String ACTUATOR_HEALTH = "/actuator/health";

    private VidIngestApiPaths() {
        throw new UnsupportedOperationException("Utility class");
    }
}
