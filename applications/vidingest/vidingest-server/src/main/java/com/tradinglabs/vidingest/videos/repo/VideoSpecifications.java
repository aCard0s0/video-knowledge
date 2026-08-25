package com.tradinglabs.vidingest.videos.repo;

import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.domain.VideoStatus;
import org.springframework.data.jpa.domain.Specification;

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

    public static Specification<Video> hasChannelName(String channelName) {
        return (root, query, cb) -> (channelName == null || channelName.isBlank())
                ? cb.conjunction()
                : cb.equal(root.get("channelName"), channelName);
    }

    public static Specification<Video> filter(VideoStatus status, String source, String channelName) {
        return Specification.allOf(hasStatus(status), hasSource(source), hasChannelName(channelName));
    }
}
