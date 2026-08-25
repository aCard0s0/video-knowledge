package com.tradinglabs.vidingest.youtube.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "vidingest.youtube.sync")
public class YoutubeSyncProperties {
    private boolean enabled = true;
    private String cron = "0 0/30 * * * *";
    private int playlistLimit = 200;
    private long timeoutSeconds = 120;
}

