package com.tradinglabs.vidingest.connections.domain;

import com.tradinglabs.vidingest.api.connections.ConnectionName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * A stored override for one runtime's connection settings.
 *
 * <p>A row means "ignore what the environment said for this connection". Absent, the
 * environment-bound {@code @ConfigurationProperties} value applies. That is the whole model: the
 * table is not the source of truth for configuration, it is the delta on top of it, which is what
 * lets {@code DELETE /api/v1/connections/{name}} mean something.
 *
 * <p>The primary key is the {@link ConnectionName} itself rather than a generated UUID — see
 * {@code 008-connections.sql} for why.
 */
@Entity
@Table(name = "vidingest_connections")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Connection {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "name", nullable = false, length = 64)
    private ConnectionName name;

    @Column(name = "provider", length = 64)
    private String provider;

    // Nullable since 009: FRAME_SAMPLE is local ffmpeg and overrides only `enabled`.
    @Column(name = "base_url", length = 2000)
    private String baseUrl;

    @Column(name = "model")
    private String model;

    @Column(name = "api_key")
    private String apiKey;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // Stamped app-side rather than by the column default, same as every other entity here, so the
    // value the JVM persists and the value it reads back are the same instant.
    @PrePersist
    @PreUpdate
    void stampUpdatedAt() {
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
