package com.tradinglabs.vidingest.config;

import javax.sql.DataSource;

import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit Liquibase wiring.
 *
 * Spring Boot 4.x does not guarantee Liquibase auto-configuration is present
 * on the classpath in all setups. Creating a SpringLiquibase bean ensures
 * migrations run at startup whenever liquibase-core is on the classpath.
 */
@Configuration
public class LiquibaseConfig {

    @Bean
    public SpringLiquibase liquibase(
            DataSource dataSource,
            @Value("${spring.liquibase.enabled:true}") boolean enabled,
            @Value("${spring.liquibase.change-log:classpath:db/changelog/db.changelog-master.yaml}") String changeLog
    ) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setShouldRun(enabled);
        liquibase.setChangeLog(changeLog);
        return liquibase;
    }
}


