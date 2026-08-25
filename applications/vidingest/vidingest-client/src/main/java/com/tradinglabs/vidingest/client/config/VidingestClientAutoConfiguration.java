package com.tradinglabs.vidingest.client.config;

import com.tradinglabs.vidingest.client.VidingestClient;
import com.tradinglabs.vidingest.client.VidingestClientProperties;
import com.tradinglabs.web.client.CorrelationIdClientHttpRequestInterceptor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@AutoConfiguration
@EnableConfigurationProperties(VidingestClientProperties.class)
public class VidingestClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "vidingestHttpClient")
    public RestClient vidingestHttpClient(VidingestClientProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.toIntExact(properties.connectTimeout().toMillis()));
        requestFactory.setReadTimeout(Math.toIntExact(properties.readTimeout().toMillis()));

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .requestInterceptor(new CorrelationIdClientHttpRequestInterceptor())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public VidingestClient vidingestClient(
            @Qualifier("vidingestHttpClient") RestClient vidingestHttpClient
    ) {
        return new VidingestClient(vidingestHttpClient);
    }
}

