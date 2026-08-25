package com.tradinglabs.vidingest.youtube.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class YoutubeSyncExecutorConfig {

    @Bean(name = "vidingestYoutubeSyncExecutor", destroyMethod = "close")
    ExecutorService vidingestYoutubeSyncExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}

