package com.tradinglabs.vidingest.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClient bean for the {@code diarize-asr} sidecar. Mirrors
 * {@link WhisperRestClientConfig} — same pattern, separate base URL + timeouts so
 * diarization (typically slower than ASR on CPU) can have its own read timeout.
 */
@Configuration
public class DiarizationRestClientConfig {

    @Bean
    @ConditionalOnMissingBean(name = "diarizationRestClient")
    public RestClient diarizationRestClient(DiarizationConfig properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.toIntExact(properties.getConnectTimeout().toMillis()));
        requestFactory.setReadTimeout(Math.toIntExact(properties.getReadTimeout().toMillis()));

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
