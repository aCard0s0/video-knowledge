package com.tradinglabs.vidingest.youtube.service;

import com.tradinglabs.vidingest.api.youtube.YoutubeChannelSummary;
import com.tradinglabs.vidingest.youtube.domain.YoutubeChannel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class YoutubeChannelMapper {

    public YoutubeChannelSummary toSummary(YoutubeChannel channel, Long videoCount) {
        return new YoutubeChannelSummary(
                channel.getId() != null ? channel.getId().toString() : null,
                channel.getChannelUrl(),
                channel.getDisplayName(),
                channel.getStatus() != null ? channel.getStatus().name() : null,
                channel.getLastSyncSuccessAt(),
                videoCount,
                channel.getLastError()
        );
    }
}

