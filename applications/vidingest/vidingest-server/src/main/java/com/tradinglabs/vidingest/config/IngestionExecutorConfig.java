package com.tradinglabs.vidingest.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class IngestionExecutorConfig {

    @Bean(name = "vidingestIngestionExecutor", destroyMethod = "close")
    ExecutorService vidingestIngestionExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}

