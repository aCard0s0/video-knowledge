package com.tradinglabs.vidingest.youtube.scheduler;

import com.tradinglabs.vidingest.youtube.config.YoutubeSyncProperties;
import com.tradinglabs.vidingest.youtube.repo.YoutubeChannelRepository;
import com.tradinglabs.vidingest.youtube.service.YoutubeChannelCommandService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
@ConditionalOnProperty(prefix = "vidingest.youtube.sync", name = "enabled", havingValue = "true", matchIfMissing = true)
public class YoutubeChannelSyncScheduler {

    private final YoutubeSyncProperties properties;
    private final YoutubeChannelRepository youtubeChannelRepository;
    private final YoutubeChannelCommandService youtubeChannels;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final ExecutorService youtubeSyncExecutor;

    /**
     * Caps concurrent yt-dlp playlist fetches. A tick submits one task per enabled channel and
     * the executor is unbounded, so without this a 200-channel tenant forks 200 yt-dlp
     * processes at once. Mirrors the gate in {@code PipelineService}; the executor stays
     * virtual-thread-per-task so shutdown waits only for in-flight work.
     */
    private final Semaphore syncGate;

    public YoutubeChannelSyncScheduler(
            YoutubeSyncProperties properties,
            YoutubeChannelRepository youtubeChannelRepository,
            YoutubeChannelCommandService youtubeChannels,
            @Qualifier("vidingestYoutubeSyncExecutor") ExecutorService youtubeSyncExecutor
    ) {
        this.properties = properties;
        this.youtubeChannelRepository = youtubeChannelRepository;
        this.youtubeChannels = youtubeChannels;
        this.youtubeSyncExecutor = youtubeSyncExecutor;
        this.syncGate = new Semaphore(Math.max(1, properties.getConcurrency()));
    }

    @Scheduled(cron = "${vidingest.youtube.sync.cron:0 0/30 * * * *}")
    public void tick() {
        if (!running.compareAndSet(false, true)) {
            log.info("YouTube sync tick skipped; previous tick still running");
            return;
        }

        try {
            var channels = youtubeChannelRepository.findAll();
            if (channels.isEmpty()) {
                return;
            }

            log.info("YouTube sync tick starting. channels={} concurrency={} playlistLimit={} timeoutSeconds={}",
                    channels.size(), properties.getConcurrency(), properties.getPlaylistLimit(),
                    properties.getTimeoutSeconds());

            List<Future<?>> futures = new ArrayList<>(channels.size());
            for (var channel : channels) {
                futures.add(youtubeSyncExecutor.submit(() -> {
                    try {
                        syncGate.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        youtubeChannels.syncChannel(channel.getId());
                    } catch (Exception e) {
                        log.warn("YouTube sync failed. channelId={} message={}", channel.getId(), e.getMessage());
                    } finally {
                        syncGate.release();
                    }
                }));
            }

            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    log.warn("YouTube sync task join failed. message={}", e.getMessage());
                }
            }
        } finally {
            running.set(false);
        }
    }
}

