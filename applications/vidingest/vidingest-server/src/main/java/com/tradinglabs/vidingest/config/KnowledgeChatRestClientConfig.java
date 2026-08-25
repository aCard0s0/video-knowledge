package com.tradinglabs.vidingest.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClient bean for the knowledge-extraction LLM (M6). Fourth {@code RestClient} in the
 * server alongside whisper/diarize/ocr — clients disambiguate via {@code @Qualifier} on
 * their constructor parameters.
 */
@Configuration
public class KnowledgeChatRestClientConfig {

    @Bean
    @ConditionalOnMissingBean(name = "knowledgeChatRestClient")
    public RestClient knowledgeChatRestClient(KnowledgeExtractionConfig properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.toIntExact(properties.getConnectTimeout().toMillis()));
        requestFactory.setReadTimeout(Math.toIntExact(properties.getReadTimeout().toMillis()));

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
