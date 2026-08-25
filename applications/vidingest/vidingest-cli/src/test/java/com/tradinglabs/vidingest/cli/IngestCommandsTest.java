package com.tradinglabs.vidingest.cli;

import com.tradinglabs.vidingest.api.videos.DeleteVideoResult;
import com.tradinglabs.vidingest.api.videos.VideoSummary;
import com.tradinglabs.vidingest.client.VidingestClient;
import com.tradinglabs.vidingest.client.VidingestClientProperties;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IngestCommandsTest {

    @Test
    void ingestRejectsBlankUrlWithoutCallingServer() {
        VidingestClient client = mock(VidingestClient.class);
        VidingestClientProperties properties = mock(VidingestClientProperties.class);
        IngestCommands commands = new IngestCommands(client, properties);

        // Blank URLs short-circuit before any client call, whatever the opt-out list says.
        String out = commands.ingest("  ", null, "DIARIZE,FRAME_SAMPLE,OCR,KNOWLEDGE", /* dryRun */ false);

        assertThat(out).contains("ERROR [Validation]");
        verifyNoInteractions(client);
    }

    @Test
    void deleteRequiresForceAndShowsConfirmation() {
        VidingestClient client = mock(VidingestClient.class);
        VidingestClientProperties properties = mock(VidingestClientProperties.class);
        IngestCommands commands = new IngestCommands(client, properties);

        UUID id = UUID.randomUUID();
        when(client.getVideo(id)).thenReturn(new VideoSummary(
                id.toString(),
                "pipeline-id",
                "Some Title",
                "youtube",
                "abc",
                "COMPLETED",
                "/data/videos/abc.mp4",
                "Channel",
                "2026-04-28T12:00:00"
        ));

        String out = commands.delete(id.toString(), false);

        assertThat(out).contains("About to delete video");
        assertThat(out).contains("Run again with --force true");
    }

    @Test
    void deleteWithForceCallsServer() {
        VidingestClient client = mock(VidingestClient.class);
        VidingestClientProperties properties = mock(VidingestClientProperties.class);
        IngestCommands commands = new IngestCommands(client, properties);

        UUID id = UUID.randomUUID();
        when(client.deleteVideo(id)).thenReturn(new DeleteVideoResult("deleted", id.toString()));

        String out = commands.delete(id.toString(), true);

        assertThat(out).contains("deleted successfully");
    }
}

