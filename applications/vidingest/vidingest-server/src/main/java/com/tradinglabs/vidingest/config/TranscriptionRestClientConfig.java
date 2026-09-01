package com.tradinglabs.vidingest.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The transcription transport. Note the absence of {@code .baseUrl(...)}: the clients build an
 * absolute URI per call from the live {@link TranscriptionClientProperties}, so that repointing
 * the connection through the settings API takes effect without recreating this bean. What the
 * bean still contributes is the request factory, and therefore the timeouts — those stay
 * startup-bound.
 */
@Configuration
public class TranscriptionRestClientConfig {

    @Bean
    @ConditionalOnMissingBean(name = "transcriptionRestClient")
    public RestClient transcriptionRestClient(TranscriptionClientProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.toIntExact(properties.getConnectTimeout().toMillis()));
        requestFactory.setReadTimeout(Math.toIntExact(properties.getReadTimeout().toMillis()));

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
