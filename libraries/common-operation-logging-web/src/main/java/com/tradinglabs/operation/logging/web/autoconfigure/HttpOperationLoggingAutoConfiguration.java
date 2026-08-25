package com.tradinglabs.operation.logging.web.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.operation.logging.web.ControllerOperationContextAspect;
import com.tradinglabs.operation.logging.web.HttpOperationLoggingFilter;
import com.tradinglabs.operation.logging.web.OperationSummaryExtractor;
import com.tradinglabs.operation.logging.web.RestOperationInputEnricher;
import com.tradinglabs.operation.logging.web.RestOperationNameResolver;
import com.tradinglabs.operation.logging.web.support.BestMatchingPatternRestOperationNameResolver;
import com.tradinglabs.operation.logging.web.support.DefaultOperationSummaryExtractor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.Ordered;

import java.util.List;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({HttpOperationLoggingFilter.class, FilterRegistrationBean.class})
@EnableConfigurationProperties(HttpOperationLoggingProperties.class)
public class HttpOperationLoggingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RestOperationNameResolver restOperationNameResolver() {
        return new BestMatchingPatternRestOperationNameResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    OperationSummaryExtractor operationSummaryExtractor(ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper mapper = objectMapperProvider.getIfAvailable(() -> new ObjectMapper().findAndRegisterModules());
        return new DefaultOperationSummaryExtractor(mapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "tradinglabs.http.logging", name = "enabled", havingValue = "true")
    FilterRegistrationBean<HttpOperationLoggingFilter> httpOperationLoggingFilter(
            HttpOperationLoggingProperties properties,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            RestOperationNameResolver operationNameResolver,
            OperationSummaryExtractor summaryExtractor,
            ObjectProvider<RestOperationInputEnricher> inputEnrichers,
            Environment environment
    ) {
        ObjectMapper mapper = objectMapperProvider.getIfAvailable(() -> new ObjectMapper().findAndRegisterModules());
        List<RestOperationInputEnricher> enrichers = inputEnrichers.orderedStream().toList();

        String appName = environment.getProperty("spring.application.name", "");
        String defaultEventName = (appName != null && !appName.isBlank())
                ? appName.trim() + ".operation"
                : "tradinglabs.operation";

        HttpOperationLoggingFilter filter = new HttpOperationLoggingFilter(
                properties,
                mapper,
                operationNameResolver,
                summaryExtractor,
                enrichers,
                defaultEventName
        );

        FilterRegistrationBean<HttpOperationLoggingFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("httpOperationLoggingFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + properties.getFilterOrderOffset());
        return registration;
    }

    @Bean
    @ConditionalOnProperty(prefix = "tradinglabs.http.logging", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "tradinglabs.http.logging", name = "include-controller-context", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    ControllerOperationContextAspect controllerOperationContextAspect(
            RestOperationNameResolver operationNameResolver,
            OperationSummaryExtractor operationSummaryExtractor
    ) {
        return new ControllerOperationContextAspect(operationNameResolver, operationSummaryExtractor);
    }
}

