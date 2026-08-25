package com.tradinglabs.vidingest.youtube.repo;

import com.tradinglabs.vidingest.youtube.domain.YoutubeChannel;
import com.tradinglabs.vidingest.youtube.domain.YoutubeChannelStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface YoutubeChannelRepository extends JpaRepository<YoutubeChannel, UUID> {
    Optional<YoutubeChannel> findByChannelUrl(String channelUrl);

    List<YoutubeChannel> findAllByStatusNot(YoutubeChannelStatus status);
}

