package com.tradinglabs.vidingest.api.pipeline;

import java.util.List;

/**
 * What this deployment will actually do, as opposed to what a request may ask for.
 *
 * <p>Every optional phase gates on a {@code vidingest.<phase>.enabled} property as well as on the
 * run's own opt-out, and most of them default to {@code false}. A client had no way to read that,
 * so the console's phase picker showed all seven ticked over "all optional phases enabled" while
 * the server was configured to skip four of them — a hundred videos submitted for OCR and
 * knowledge extraction came back with neither, and nothing on the screen had said so.
 *
 * @param enabledPhases   the optional phases this server will run when a request does not skip
 *                        them, in pipeline order. Names match {@code PipelineRunPhase}.
 * @param maxUrlsPerRun   the batch ceiling both create endpoints validate against.
 * @param channelSyncLimit how many uploads a channel sync fetches ({@code --playlist-end}). The
 *                        catalog is a window onto the newest N, not the channel's size, and
 *                        "200 in catalog" read as a count until the console could say otherwise.
 */
public record PipelineCapabilities(
        List<String> enabledPhases,
        int maxUrlsPerRun,
        int channelSyncLimit
) {
}
