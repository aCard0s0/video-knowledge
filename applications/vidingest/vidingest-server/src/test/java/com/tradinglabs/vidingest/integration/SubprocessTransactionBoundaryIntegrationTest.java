package com.tradinglabs.vidingest.integration;

import com.tradinglabs.vidingest.core.download.service.DownloadService;
import com.tradinglabs.vidingest.core.download.service.VideoDownloadService;
import com.tradinglabs.vidingest.youtube.discovery.YoutubeChannelDiscoveryResult;
import com.tradinglabs.vidingest.youtube.discovery.YoutubeChannelDiscoveryService;
import com.tradinglabs.vidingest.youtube.domain.YoutubeChannel;
import com.tradinglabs.vidingest.youtube.domain.YoutubeChannelStatus;
import com.tradinglabs.vidingest.youtube.repo.YoutubeChannelRepository;
import com.tradinglabs.vidingest.youtube.service.YoutubeChannelCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * yt-dlp must never run inside a transaction. The pool is ten connections; a download is bounded
 * by {@code vidingest.download.timeout-seconds} (1800 by default) and a channel sync by
 * {@code vidingest.youtube.sync.timeoutSeconds} at four concurrent ticks. Holding a connection
 * across either starves every other DB user in the JVM — phase writes, audit inserts, the
 * readiness probe.
 *
 * <p>Same shape as the guard in {@code ContextChunkRegenerateIntegrationTest}: assert inside the
 * stubbed subprocess call, so this fails the day someone re-adds {@code @Transactional} rather
 * than the day production wedges.
 */
class SubprocessTransactionBoundaryIntegrationTest extends BaseVidingestIntegrationTest {

    @Autowired
    private DownloadService downloadService;

    @Autowired
    private YoutubeChannelCommandService youtubeChannels;

    @Autowired
    private YoutubeChannelRepository youtubeChannelRepository;

    @MockitoBean
    private VideoDownloadService videoDownloadService;

    @MockitoBean
    private YoutubeChannelDiscoveryService discoveryService;

    @Test
    void downloadToDatabaseRunsYtDlpOutsideAnyTransaction() throws Exception {
        AtomicBoolean downloadRan = new AtomicBoolean();
        Path videoFile = Files.createTempFile("vidingest-tx-guard-", ".mp4");

        when(videoDownloadService.extractMetadata(anyString())).thenAnswer(inv -> {
            assertNoTransaction("yt-dlp metadata fetch");
            return Map.of("extractor", "youtube", "id", "txguard001", "title", "Guarded");
        });
        when(videoDownloadService.downloadVideoToDisk(anyString(), org.mockito.ArgumentMatchers.anyMap(), anyBoolean()))
                .thenAnswer(inv -> {
                    assertNoTransaction("yt-dlp download");
                    downloadRan.set(true);
                    return videoFile.toString();
                });
        when(videoDownloadService.saveMetadataToDisk(org.mockito.ArgumentMatchers.anyMap(), anyString()))
                .thenReturn(videoFile + ".info.json");

        var video = downloadService.downloadToDatabase("https://www.youtube.com/watch?v=txguard001", false);

        assertThat(downloadRan).isTrue();
        assertThat(video.getId()).isNotNull();
        assertThat(video.getSourceVideoId()).isEqualTo("txguard001");
        Files.deleteIfExists(videoFile);
    }

    @Test
    void syncChannelRunsYtDlpOutsideAnyTransaction() throws Exception {
        UUID channelId = newChannel();

        when(discoveryService.discover(anyString(), anyInt(), anyLong())).thenAnswer(inv -> {
            assertNoTransaction("yt-dlp playlist fetch");
            return new YoutubeChannelDiscoveryResult(
                    inv.getArgument(0), "UC_guarded", "Guarded channel", Map.of(), List.of());
        });

        youtubeChannels.syncChannel(channelId);

        assertThat(youtubeChannelRepository.findById(channelId))
                .get()
                .extracting(YoutubeChannel::getStatus)
                .isEqualTo(YoutubeChannelStatus.READY);
    }

    /**
     * The error path used to be written inside the transaction it was about to roll back, so a
     * RuntimeException left the row with its old status and a null lastError — a sync that failed
     * this way was invisible in the API.
     */
    @Test
    void syncChannelRecordsARuntimeFailureOnTheChannelRow() throws Exception {
        UUID channelId = newChannel();

        when(discoveryService.discover(anyString(), anyInt(), anyLong()))
                .thenThrow(new IllegalStateException("playlist json was garbage"));

        assertThatThrownBy(() -> youtubeChannels.syncChannel(channelId))
                .isInstanceOf(IllegalStateException.class);

        YoutubeChannel after = youtubeChannelRepository.findById(channelId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(YoutubeChannelStatus.ERROR);
        assertThat(after.getLastError()).contains("playlist json was garbage");
        assertThat(after.getLastSyncAttemptAt()).isNotNull();
    }

    private UUID newChannel() {
        return youtubeChannelRepository.save(YoutubeChannel.builder()
                .channelUrl("https://www.youtube.com/@guarded" + UUID.randomUUID())
                .status(YoutubeChannelStatus.NEW)
                .build()).getId();
    }

    private static void assertNoTransaction(String what) throws IOException {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as(what + " must not hold a DB connection")
                .isFalse();
    }
}
