package com.tradinglabs.vidingest.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LiquibaseMigrationsIntegrationTest extends BaseVidingestIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void pgvectorExtensionAndKeyTablesExist() {
        Integer vectorCount = jdbcTemplate.queryForObject(
                "select count(*) from pg_extension where extname = 'vector'",
                Integer.class
        );
        assertThat(vectorCount).isNotNull();
        assertThat(vectorCount).isGreaterThanOrEqualTo(1);

        List<String> requiredTables = List.of(
                "vidingest_videos",
                "vidingest_pipeline_runs",
                "vidingest_pipeline_run_items",
                "vidingest_transcriptions",
                "vidingest_transcription_segments",
                "vidingest_context_chunks",
                "vidingest_youtube_channels",
                "vidingest_youtube_channel_videos"
        );

        for (String table : requiredTables) {
            String regclass = jdbcTemplate.queryForObject(
                    "select to_regclass('public.' || ?)",
                    String.class,
                    table
            );
            assertThat(regclass).as("table exists: %s".formatted(table)).isNotNull();
            assertThat(regclass).as("table exists: %s".formatted(table)).endsWith(table);
        }
    }
}

