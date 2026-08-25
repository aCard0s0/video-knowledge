package com.tradinglabs.vidingest.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "vidingest.whisper")
public class WhisperClientProperties {

    /**
     * Whisper ASR webservice base URL (e.g. http://localhost:9000 or http://whisper:9000 in Docker).
     */
    private String baseUrl = "http://localhost:9000";

    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * Transcription can take a while for long inputs.
     */
    private Duration readTimeout = Duration.ofMinutes(30);
}

