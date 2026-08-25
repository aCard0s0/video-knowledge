package com.tradinglabs.vidingest.youtube.scheduler;

import com.tradinglabs.vidingest.youtube.config.YoutubeSyncProperties;
import com.tradinglabs.vidingest.youtube.domain.YoutubeChannelStatus;
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
    }

    @Scheduled(cron = "${vidingest.youtube.sync.cron:0 0/30 * * * *}")
    public void tick() {
        if (!running.compareAndSet(false, true)) {
            log.info("YouTube sync tick skipped; previous tick still running");
            return;
        }

        try {
            var channels = youtubeChannelRepository.findAllByStatusNot(YoutubeChannelStatus.DISABLED);
            if (channels.isEmpty()) {
                return;
            }

            log.info("YouTube sync tick starting. channels={} playlistLimit={} timeoutSeconds={}",
                    channels.size(), properties.getPlaylistLimit(), properties.getTimeoutSeconds());

            List<Future<?>> futures = new ArrayList<>(channels.size());
            for (var channel : channels) {
                futures.add(youtubeSyncExecutor.submit(() -> {
                    try {
                        youtubeChannels.syncChannel(channel.getId());
                    } catch (Exception e) {
                        log.warn("YouTube sync failed. channelId={} message={}", channel.getId(), e.getMessage());
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

