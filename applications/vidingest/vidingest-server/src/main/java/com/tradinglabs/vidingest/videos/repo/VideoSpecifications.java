package com.tradinglabs.vidingest.videos.repo;

import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;

public final class VideoSpecifications {

    private VideoSpecifications() {
    }

    public static Specification<Video> hasStatus(VideoStatus status) {
        return (root, query, cb) -> status == null
                ? cb.conjunction()
                : cb.equal(root.get("status"), status);
    }

    public static Specification<Video> hasSource(String source) {
        return (root, query, cb) -> (source == null || source.isBlank())
                ? cb.conjunction()
                : cb.equal(root.get("source"), source);
    }

    /**
     * Substring, case-insensitive, because the console renders this filter as a search box.
     *
     * It was {@code cb.equal}: typing {@code comp} returned nothing while nine Computerphile videos
     * sat in the table under the "No videos match this filter" empty state. The only ways to use it
     * were to know a channel's name exactly, or to arrive from channel detail's "Ingested videos →"
     * link, which passes {@code displayName} verbatim — that link still matches, now as a substring
     * of itself. {@code source} above is deliberately left exact: it is an extractor id from
     * yt-dlp, not something anyone types a fragment of.
     *
     * The leading wildcard means {@code idx_vidingest_videos_channel_name} can no longer serve this
     * predicate. Left in place rather than dropped in the same change: it is the only index on the
     * column, and the corpus this scans is thousands of rows on one operator's box.
     *
     * {@code %} and {@code _} reach LIKE unescaped. The value is a bound parameter either way, so
     * this is matching behaviour and not injection — a wildcard someone typed on purpose works.
     */
    public static Specification<Video> hasChannelName(String channelName) {
        return (root, query, cb) -> (channelName == null || channelName.isBlank())
                ? cb.conjunction()
                : cb.like(cb.lower(root.get("channelName")), "%" + channelName.toLowerCase(Locale.ROOT) + "%");
    }

    public static Specification<Video> filter(VideoStatus status, String source, String channelName) {
        return Specification.allOf(hasStatus(status), hasSource(source), hasChannelName(channelName));
    }
}
