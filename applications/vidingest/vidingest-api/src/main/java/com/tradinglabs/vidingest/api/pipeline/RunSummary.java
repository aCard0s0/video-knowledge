package com.tradinglabs.vidingest.api.pipeline;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of the runs list.
 *
 * <p>Ids and instants are typed, and an absent value is {@code null} rather than {@code ""}. The
 * older all-{@code String} shape made every mapper stringify and null-guard by hand, and it could
 * not distinguish "no error" from "an empty error" — which matters here, because
 * {@code RunLiveSummaryService} serves this same record from a projection that carries none of the
 * video fields at all.
 *
 * <p>{@code status}, {@code phase} and {@code errorCode} are still {@code String}, and that is
 * measured rather than lazy: typing them needs the server enums moved into this module, and
 * springdoc <em>inlines</em> an enum per property instead of {@code $ref}-ing a component schema,
 * so the generated console client would gain one TS enum per DTO rather than one shared union —
 * and a TS string enum is not comparable to a string literal. Finding 7 of
 * {@code docs/vidingest/VidIngest - Web UI API Findings.md} carries the numbers and the two ways
 * out.
 */
public record RunSummary(
        UUID id,
        String status,
        String phase,
        String errorCode,
        String error,
        String videoUrl,
        UUID videoId,
        String channelName,
        String videoTitle,
        int videoCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
