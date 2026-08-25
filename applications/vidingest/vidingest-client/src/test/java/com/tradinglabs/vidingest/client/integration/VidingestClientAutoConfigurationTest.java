package com.tradinglabs.vidingest.client.integration;

import com.tradinglabs.vidingest.client.VidingestClient;
import com.tradinglabs.vidingest.client.VidingestClientProperties;
import com.tradinglabs.vidingest.client.config.VidingestClientAutoConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class VidingestClientAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(VidingestClientAutoConfiguration.class));

    @Test
    void autoConfigurationRegistersDefaultClientBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("vidingestHttpClient");
            assertThat(context).hasSingleBean(VidingestClient.class);
            assertThat(context).hasSingleBean(VidingestClientProperties.class);
            assertThat(context).hasSingleBean(RestClient.class);

            VidingestClientProperties properties = context.getBean(VidingestClientProperties.class);
            assertThat(properties.baseUrl()).isEqualTo("http://localhost:8051/vidingest");
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofMinutes(10));
        });
    }

    @Test
    void autoConfigurationBacksOffWhenVidingestHttpClientIsProvided() {
        contextRunner
                .withUserConfiguration(OverrideHttpClientConfig.class)
                .run(context -> {
                    RestClient provided = context.getBean("vidingestHttpClient", RestClient.class);
                    RestClient fromOverrideConfig = context.getBean("customVidingestHttpClient", RestClient.class);

                    assertThat(provided).isSameAs(fromOverrideConfig);
                });
    }

    @Test
    void autoConfigurationBacksOffWhenVidingestClientIsProvided() {
        contextRunner
                .withUserConfiguration(OverrideClientConfig.class)
                .run(context -> {
                    VidingestClient provided = context.getBean(VidingestClient.class);
                    VidingestClient fromOverrideConfig = context.getBean("customVidingestClient", VidingestClient.class);

                    assertThat(provided).isSameAs(fromOverrideConfig);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class OverrideHttpClientConfig {
        @Bean(name = {"customVidingestHttpClient", "vidingestHttpClient"})
        RestClient vidingestHttpClientOverride() {
            return RestClient.builder().baseUrl("http://localhost:9999/override").build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OverrideClientConfig {
        @Bean(name = "customVidingestClient")
        VidingestClient vidingestClientOverride(RestClient vidingestHttpClient) {
            return new VidingestClient(vidingestHttpClient);
        }
    }
}

