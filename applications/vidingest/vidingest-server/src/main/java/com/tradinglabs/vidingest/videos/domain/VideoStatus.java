package com.tradinglabs.vidingest.videos.domain;

/**
 * Status of a video in the ingestion pipeline.
 */
public enum VideoStatus {
    PENDING,        // Video URL received but not downloaded
    DOWNLOADING,    // Download in progress
    DOWNLOADED,     // Video downloaded successfully
    EXTRACTING,     // Extracting metadata
    TRANSCRIBING,   // Transcription in progress
    PROCESSING,     // Post-processing (chunking, embeddings)
    COMPLETED,      // Fully processed
    FAILED          // Processing failed
}

