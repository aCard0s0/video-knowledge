package com.tradinglabs.vidingest.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Configuration to explicitly enable transaction management
 * Ensures @Transactional annotations work properly in CLI environment
 */
@Configuration
@EnableTransactionManagement
public class TransactionConfig {
    // Spring Boot auto-configures transaction management when spring-boot-starter-data-jpa is present,
    // but we explicitly enable it here to ensure it works in the CLI/Shell environment
}



