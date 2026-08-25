package com.tradinglabs.vidingest.youtube.repo;

import com.tradinglabs.vidingest.youtube.domain.YoutubeChannelVideo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface YoutubeChannelVideoRepository extends JpaRepository<YoutubeChannelVideo, UUID> {

    Page<YoutubeChannelVideo> findAllByChannel_Id(UUID channelId, Pageable pageable);

    long countByChannel_Id(UUID channelId);

    List<YoutubeChannelVideo> findAllByChannel_IdAndYoutubeVideoIdIn(UUID channelId, Collection<String> youtubeVideoIds);
}

