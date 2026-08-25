package com.tradinglabs.vidingest.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for video storage paths
 * Maps properties from application.properties with prefix "vidingest.storage"
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "vidingest.storage")
public class VideoStorageConfig {

    /**
     * Video storage path (optional, defaults to project path resolver)
     */
    private String videoPath;
}



