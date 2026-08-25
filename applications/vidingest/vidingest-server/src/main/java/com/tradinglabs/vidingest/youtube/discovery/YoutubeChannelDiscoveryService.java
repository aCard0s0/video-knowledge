package com.tradinglabs.vidingest.youtube.discovery;

import com.tradinglabs.vidingest.core.download.util.YtDlpCommandBuilder;
import com.tradinglabs.vidingest.core.download.util.YtDlpExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@RequiredArgsConstructor
@Slf4j
public class YoutubeChannelDiscoveryService {

    private final YtDlpCommandBuilder ytDlpCommandBuilder;
    private final YoutubeChannelDiscoveryParser parser;

    public YoutubeChannelDiscoveryResult discover(String channelUrl, int playlistLimit, long timeoutSeconds) throws IOException {
        if (channelUrl == null || channelUrl.isBlank()) {
            throw new IllegalArgumentException("channelUrl must not be blank");
        }

        var cmd = ytDlpCommandBuilder.buildChannelListingCommand(channelUrl, playlistLimit);
        String json = YtDlpExecutor.executeForOutput(cmd, timeoutSeconds);
        return parser.parse(channelUrl.trim(), json);
    }
}

