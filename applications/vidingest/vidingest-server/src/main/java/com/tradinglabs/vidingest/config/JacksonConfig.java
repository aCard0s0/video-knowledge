package com.tradinglabs.vidingest.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 2 mapper for the code that asks for one directly (yt-dlp metadata, sidecar payloads).
 * Spring Boot 4 binds HTTP request bodies with Jackson 3, so this bean does <em>not</em> govern
 * the REST edge — {@code spring.jackson.deserialization.fail-on-unknown-properties} does.
 */
@Configuration
public class JacksonConfig {

    @Bean
    ObjectMapper objectMapper() {
        return JsonMapper.builder().findAndAddModules().build();
    }
}
