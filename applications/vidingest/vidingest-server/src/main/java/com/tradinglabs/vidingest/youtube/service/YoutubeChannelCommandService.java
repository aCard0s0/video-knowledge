package com.tradinglabs.vidingest.youtube.service;

import com.tradinglabs.vidingest.api.youtube.CreatePipelineRunFromYoutubeVideosRequest;
import com.tradinglabs.vidingest.api.youtube.CreateYoutubeChannelRequest;
import com.tradinglabs.vidingest.api.youtube.YoutubeChannelSummary;
import com.tradinglabs.vidingest.api.youtube.YoutubeChannelVideoSummary;
import com.tradinglabs.vidingest.api.common.PageResponse;
import com.tradinglabs.vidingest.commons.ConflictException;
import com.tradinglabs.vidingest.api.pipeline.CreatePipelineRunResponse;
import com.tradinglabs.vidingest.pipeline.service.PipelineIntakeService;
import com.tradinglabs.vidingest.pipeline.util.SkipPhasesParser;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import com.tradinglabs.vidingest.youtube.config.YoutubeSyncProperties;
import com.tradinglabs.vidingest.youtube.discovery.YoutubeChannelDiscoveryResult;
import com.tradinglabs.vidingest.youtube.discovery.YoutubeChannelDiscoveryService;
import com.tradinglabs.vidingest.youtube.domain.YoutubeChannel;
import com.tradinglabs.vidingest.youtube.domain.YoutubeChannelStatus;
import com.tradinglabs.vidingest.youtube.domain.YoutubeChannelVideo;
import com.tradinglabs.vidingest.youtube.exceptions.YoutubeChannelNotFoundException;
import com.tradinglabs.vidingest.youtube.repo.YoutubeChannelRepository;
import com.tradinglabs.vidingest.youtube.repo.YoutubeChannelVideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class YoutubeChannelCommandService {

    /**
     * Newest upload first, which is the only order the channel screen is for.
     *
     * {@code publishedAt} is null on every row a {@code --flat-playlist} discovery produces —
     * yt-dlp does not emit upload dates for a channel tab — so in practice the two columns behind
     * it carry the order. {@code firstSeenAt} is one timestamp per sync batch, and {@code createdAt}
     * is stamped per row in {@code @PrePersist} while {@link #upsertVideos} saves the discovered
     * list in one ordered pass, and yt-dlp lists newest first. So later batches sort above earlier
     * ones, and inside a batch the discovery order survives.
     *
     * <p>Sorting {@code createdAt} descending reversed each batch instead: on a freshly synced
     * 200-video catalog every upload arrives in one batch, so page 0 held the *oldest* fifty and
     * the newest upload was on the last page.
     *
     * <p>ponytail: leans on insert order matching discovery order. A stored playlist position is
     * the upgrade if the sync ever stops saving the list in one ordered pass.
     */
    private static final Sort DEFAULT_VIDEO_SORT = Sort.by(
            Sort.Order.desc("publishedAt").nullsLast(),
            Sort.Order.desc("firstSeenAt"),
            Sort.Order.asc("createdAt"));
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final YoutubeChannelRepository youtubeChannelRepository;
    private final YoutubeChannelVideoRepository youtubeChannelVideoRepository;
    private final VideoRepository videoRepository;
    private final YoutubeChannelDiscoveryService discoveryService;
    private final PipelineIntakeService pipelineIntakeService;
    private final YoutubeChannelMapper youtubeChannelMapper;
    private final YoutubeSyncProperties youtubeSyncProperties;
    private final TransactionOperations transactionOperations;

    @Transactional
    public YoutubeChannelSummary createChannel(CreateYoutubeChannelRequest request) {
        String normalizedUrl = normalizeChannelUrl(request.url());
        youtubeChannelRepository.findByChannelUrl(normalizedUrl).ifPresent(existing -> {
            throw new ConflictException("YouTube channel already exists for url: " + normalizedUrl);
        });

        YoutubeChannel channel = YoutubeChannel.builder()
                .channelUrl(normalizedUrl)
                .displayName(trimToNull(request.displayName()))
                .status(YoutubeChannelStatus.NEW)
                .build();
        youtubeChannelRepository.save(channel);
        return youtubeChannelMapper.toSummary(channel, 0L);
    }

    @Transactional(readOnly = true)
    public PageResponse<YoutubeChannelSummary> listChannels(Integer page, Integer size) {
        int pageValue = page != null ? Math.max(0, page) : 0;
        int sizeValue = size != null ? Math.clamp(size, 1, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;

        var pageResult = youtubeChannelRepository.findAll(
                PageRequest.of(pageValue, sizeValue, Sort.by(Sort.Direction.DESC, "createdAt"))
        );

        var summaries = pageResult.getContent().stream()
                .map(ch -> youtubeChannelMapper.toSummary(ch, youtubeChannelVideoRepository.countByChannel_Id(ch.getId())))
                .toList();

        return new PageResponse<>(summaries, pageResult.getNumber(), pageResult.getSize(), pageResult.getTotalElements());
    }

    @Transactional(readOnly = true)
    public YoutubeChannelSummary getChannel(UUID channelId) {
        YoutubeChannel ch = youtubeChannelRepository.findById(channelId)
                .orElseThrow(() -> new YoutubeChannelNotFoundException(channelId));
        long count = youtubeChannelVideoRepository.countByChannel_Id(channelId);
        return youtubeChannelMapper.toSummary(ch, count);
    }

    /**
     * Stops tracking a channel.
     *
     * <p>This is the only escape hatch, and it exists because there was none. A {@code DISABLED}
     * status was declared but never reachable, so a mistyped URL sat {@code ERROR} forever while
     * {@link com.tradinglabs.vidingest.youtube.scheduler.YoutubeChannelSyncScheduler} re-ran yt-dlp
     * against it every half hour. The dead constant is gone; deleting is how tracking stops.
     *
     * <p>The discovered catalog goes with it through the {@code ON DELETE CASCADE} on
     * {@code vidingest_youtube_channel_videos.channel_id}. Videos already ingested from the
     * channel are untouched: they are {@code vidingest_videos} rows with no FK back here, and
     * removing a channel is how a typo is undone, not how a corpus is wiped.
     */
    @Transactional
    public void deleteChannel(UUID channelId) {
        if (!youtubeChannelRepository.existsById(channelId)) {
            throw new YoutubeChannelNotFoundException(channelId);
        }
        youtubeChannelRepository.deleteById(channelId);
        log.info("Deleted YouTube channel: channelId={}", channelId);
    }

    /**
     * Deliberately <b>not</b> {@code @Transactional}. {@code discover} is a yt-dlp playlist fetch
     * that runs for up to {@code vidingest.youtube.sync.timeoutSeconds}; wrapping the method held a
     * pooled connection across it, four at a time per tick, against a pool of ten.
     *
     * <p>Splitting it also fixes the error path. A {@code RuntimeException} out of a transactional
     * method rolls it back — including the {@code ERROR} status and {@code lastError} the catch
     * block had just written. The channel kept its previous status and a null error, so a sync
     * that failed this way was invisible to anyone reading the row.
     */
    public YoutubeChannelSummary syncChannel(UUID channelId) throws IOException {
        // Claim the channel and read its URL in one short transaction. It commits before discovery
        // starts, so SYNCING is visible while the fetch runs and survives its failure.
        String channelUrl = transactionOperations.execute(status -> {
            YoutubeChannel ch = loadForSync(channelId);
            ch.setStatus(YoutubeChannelStatus.SYNCING);
            ch.setLastSyncAttemptAt(OffsetDateTime.now(ZoneOffset.UTC));
            ch.setLastError(null);
            return youtubeChannelRepository.save(ch).getChannelUrl();
        });

        YoutubeChannelDiscoveryResult discovery;
        try {
            discovery = discoveryService.discover(
                    channelUrl,
                    youtubeSyncProperties.getPlaylistLimit(),
                    youtubeSyncProperties.getTimeoutSeconds()
            );
        } catch (IOException | RuntimeException e) {
            recordSyncFailure(channelId, e.getMessage());
            throw e;
        }

        return transactionOperations.execute(status -> applyDiscovery(channelId, discovery));
    }

    /** Its own transaction, so it commits even though the caller is about to rethrow. */
    private void recordSyncFailure(UUID channelId, String message) {
        transactionOperations.executeWithoutResult(status ->
                youtubeChannelRepository.findById(channelId).ifPresent(ch -> {
                    ch.setStatus(YoutubeChannelStatus.ERROR);
                    ch.setLastError(message);
                    youtubeChannelRepository.save(ch);
                }));
    }

    /** Caller supplies the transaction — {@link #syncChannel} wraps this in one. */
    private YoutubeChannelSummary applyDiscovery(UUID channelId, YoutubeChannelDiscoveryResult discovery) {
        YoutubeChannel ch = loadForSync(channelId);

        if (ch.getDisplayName() == null && discovery.channelName() != null) {
            ch.setDisplayName(discovery.channelName());
        }
        ch.setMetadata(discovery.metadata());

        upsertVideos(ch, discovery.videos());

        ch.setStatus(YoutubeChannelStatus.READY);
        ch.setLastSyncSuccessAt(OffsetDateTime.now(ZoneOffset.UTC));
        ch.setLastError(null);

        youtubeChannelRepository.save(ch);
        return youtubeChannelMapper.toSummary(ch, youtubeChannelVideoRepository.countByChannel_Id(channelId));
    }

    private YoutubeChannel loadForSync(UUID channelId) {
        return youtubeChannelRepository.findById(channelId)
                .orElseThrow(() -> new YoutubeChannelNotFoundException(channelId));
    }

    /**
     * @param notIngestedOnly drops rows already ingested <em>before</em> the page is cut, so the
     *                        total the pager renders describes what the operator can actually see.
     */
    @Transactional(readOnly = true)
    public PageResponse<YoutubeChannelVideoSummary> listChannelVideos(
            UUID channelId, Integer page, Integer size, Boolean notIngestedOnly) {
        if (!youtubeChannelRepository.existsById(channelId)) {
            throw new YoutubeChannelNotFoundException(channelId);
        }

        int pageValue = page != null ? Math.max(0, page) : 0;
        int sizeValue = size != null ? Math.clamp(size, 1, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
        var pageable = PageRequest.of(pageValue, sizeValue, DEFAULT_VIDEO_SORT);

        var pageResult = Boolean.TRUE.equals(notIngestedOnly)
                ? youtubeChannelVideoRepository.findNotIngestedByChannelId(channelId, pageable)
                : youtubeChannelVideoRepository.findAllByChannel_Id(channelId, pageable);

        var ids = pageResult.getContent().stream().map(YoutubeChannelVideo::getYoutubeVideoId).toList();
        Set<String> ingested = ingestedYoutubeVideoIds(ids);

        var items = pageResult.getContent().stream()
                .map(v -> new YoutubeChannelVideoSummary(
                        v.getId() != null ? v.getId().toString() : null,
                        v.getYoutubeVideoId(),
                        v.getTitle(),
                        v.getPublishedAt(),
                        v.getWatchUrl(),
                        ingested.contains(v.getYoutubeVideoId())
                ))
                .toList();

        return new PageResponse<>(items, pageResult.getNumber(), pageResult.getSize(), pageResult.getTotalElements());
    }

    public CreatePipelineRunResponse createPipelineRun(UUID channelId, CreatePipelineRunFromYoutubeVideosRequest request) {
        if (!youtubeChannelRepository.existsById(channelId)) {
            throw new YoutubeChannelNotFoundException(channelId);
        }

        List<String> youtubeVideoIds = request.youtubeVideoIds().stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .toList();
        if (youtubeVideoIds.isEmpty()) {
            throw new IllegalArgumentException("youtubeVideoIds must not be empty");
        }

        var videos = youtubeChannelVideoRepository.findAllByChannel_IdAndYoutubeVideoIdIn(channelId, youtubeVideoIds);
        Map<String, YoutubeChannelVideo> byId = new HashMap<>();
        for (YoutubeChannelVideo v : videos) {
            byId.put(v.getYoutubeVideoId(), v);
        }

        List<String> missing = youtubeVideoIds.stream()
                .filter(id -> !byId.containsKey(id))
                .distinct()
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Unknown youtubeVideoIds for channel: " + String.join(", ", missing));
        }

        List<String> urls = youtubeVideoIds.stream()
                .map(id -> byId.get(id).getWatchUrl())
                .toList();

        // Pass the opt-out set through so channel-driven runs honour the same toggles as
        // direct user runs rather than silently enabling enrichment.
        return pipelineIntakeService.intake(urls, SkipPhasesParser.parse(request.skipPhases()));
    }

    private void upsertVideos(YoutubeChannel channel, List<YoutubeChannelDiscoveryResult.YoutubeVideoCandidate> discovered) {
        if (discovered == null || discovered.isEmpty()) {
            return;
        }

        List<String> ids = discovered.stream()
                .map(YoutubeChannelDiscoveryResult.YoutubeVideoCandidate::youtubeVideoId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();

        var existing = youtubeChannelVideoRepository.findAllByChannel_IdAndYoutubeVideoIdIn(channel.getId(), ids);
        Map<String, YoutubeChannelVideo> existingById = new HashMap<>();
        for (YoutubeChannelVideo v : existing) {
            existingById.put(v.getYoutubeVideoId(), v);
        }

        var now = OffsetDateTime.now(ZoneOffset.UTC);
        List<YoutubeChannelVideo> toSave = new java.util.ArrayList<>(ids.size());
        Set<String> processed = new HashSet<>(ids.size());

        for (YoutubeChannelDiscoveryResult.YoutubeVideoCandidate c : discovered) {
            if (c.youtubeVideoId() == null || c.youtubeVideoId().isBlank()) continue;
            String id = c.youtubeVideoId().trim();
            if (!processed.add(id)) {
                continue;
            }
            YoutubeChannelVideo v = existingById.get(id);
            if (v == null) {
                v = YoutubeChannelVideo.builder()
                        .channel(channel)
                        .youtubeVideoId(id)
                        .title(c.title())
                        .publishedAt(c.publishedAt())
                        .watchUrl(c.watchUrl())
                        .metadata(c.metadata())
                        .firstSeenAt(now)
                        .lastSeenAt(now)
                        .build();
            } else {
                v.setTitle(c.title());
                v.setPublishedAt(c.publishedAt());
                v.setWatchUrl(c.watchUrl());
                v.setMetadata(c.metadata());
                v.setLastSeenAt(now);
            }
            toSave.add(v);
        }

        youtubeChannelVideoRepository.saveAll(toSave);
        log.info("YouTube channel sync upserted {} videos. channelId={}", toSave.size(), channel.getId());
    }

    private Set<String> ingestedYoutubeVideoIds(List<String> youtubeVideoIds) {
        if (youtubeVideoIds == null || youtubeVideoIds.isEmpty()) {
            return Set.of();
        }
        var videos = videoRepository.findBySourceAndSourceVideoIdIn("youtube", youtubeVideoIds);
        Set<String> set = new HashSet<>(videos.size());
        for (var v : videos) {
            if (v.getSourceVideoId() != null) {
                set.add(v.getSourceVideoId());
            }
        }
        return Set.copyOf(set);
    }

    private static String normalizeChannelUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url must not be blank");
        }
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new IllegalArgumentException("url must start with http:// or https://");
        }
        // Light normalization so duplicates collapse.
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}

