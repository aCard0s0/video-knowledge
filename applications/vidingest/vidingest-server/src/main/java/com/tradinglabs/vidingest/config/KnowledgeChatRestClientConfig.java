package com.tradinglabs.vidingest.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Transport for the knowledge-extraction chat LLM (M6).
 *
 * <p>Note the absence of {@code .baseUrl(...)}: the client resolves an absolute URI per call from
 * the live {@link KnowledgeExtractionConfig}, so repointing the connection through
 * {@code PUT /api/v1/connections/{name}} takes effect without recreating this bean. What the bean
 * still contributes is the request factory, and therefore the timeouts — those stay startup-bound.
 *
 * <p>One of four {@code RestClient} beans in the server; clients disambiguate with
 * {@code @Qualifier} on their constructor parameters.
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
                .requestFactory(requestFactory)
                .build();
    }
}
