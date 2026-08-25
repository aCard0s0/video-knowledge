package com.tradinglabs.vidingest.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "vidingest.server")
public record VidingestClientProperties(
        @DefaultValue("http://localhost:8051/vidingest") String baseUrl,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("10m") Duration readTimeout
) {
}

