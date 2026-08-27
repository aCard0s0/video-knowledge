package com.tradinglabs.vidingest.core.diarization.controller;

import com.tradinglabs.vidingest.commons.VidingestApiExceptionHandler;
import com.tradinglabs.vidingest.core.diarization.domain.Speaker;
import com.tradinglabs.vidingest.core.diarization.mapper.SpeakerMapper;
import com.tradinglabs.vidingest.core.diarization.repo.SpeakerRepository;
import com.tradinglabs.vidingest.core.diarization.service.SpeakerService;
import com.tradinglabs.vidingest.core.transcription.repo.TranscriptionSegmentRepository;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import com.tradinglabs.vidingest.videos.service.VideoQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvc tests for {@link SpeakerController}. Covers the GET listing per video and the
 * PATCH rename endpoint, including the displayName-clearing semantics.
 *
 * <p>The real {@link SpeakerService} is imported rather than mocked: the controller is now a
 * pass-through, so mocking the service would leave these assertions testing nothing but Jackson.
 */
@WebMvcTest(controllers = SpeakerController.class)
@Import({VidingestApiExceptionHandler.class, SpeakerMapper.class,
        SpeakerService.class, VideoQueryService.class})
class SpeakerControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SpeakerRepository speakerRepository;
    @MockitoBean
    private TranscriptionSegmentRepository transcriptionSegmentRepository;
    @MockitoBean
    private VideoRepository videoRepository;

    @Test
    void listForVideoReturnsSpeakerDtosWithSegmentCount() throws Exception {
        UUID videoId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID speakerId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Video video = new Video();
        video.setId(videoId);
        when(videoRepository.existsById(videoId)).thenReturn(true);
        Speaker s = Speaker.builder()
                .id(speakerId)
                .video(video)
                .label("SPEAKER_00")
                .displayName("Alice")
                .createdAt(OffsetDateTime.parse("2026-05-13T09:00:00Z"))
                .build();
        when(speakerRepository.findByVideo_Id(videoId)).thenReturn(List.of(s));
        when(transcriptionSegmentRepository.countBySpeakerId(speakerId)).thenReturn(42L);

        mockMvc.perform(get("/api/v1/videos/{videoId}/speakers", videoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(speakerId.toString()))
                .andExpect(jsonPath("$[0].label").value("SPEAKER_00"))
                .andExpect(jsonPath("$[0].displayName").value("Alice"))
                .andExpect(jsonPath("$[0].segmentCount").value(42));
    }

    @Test
    void renameSetsDisplayName() throws Exception {
        UUID speakerId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        Video video = new Video();
        video.setId(UUID.randomUUID());
        Speaker s = Speaker.builder()
                .id(speakerId)
                .video(video)
                .label("SPEAKER_00")
                .createdAt(OffsetDateTime.parse("2026-05-13T09:00:00Z"))
                .build();
        when(speakerRepository.findById(speakerId)).thenReturn(Optional.of(s));
        when(speakerRepository.save(any(Speaker.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transcriptionSegmentRepository.countBySpeakerId(speakerId)).thenReturn(5L);

        mockMvc.perform(patch("/api/v1/speakers/{speakerId}", speakerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Bob\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Bob"));
    }

    @Test
    void renameWithBlankNameClearsOverride() throws Exception {
        UUID speakerId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        Video video = new Video();
        video.setId(UUID.randomUUID());
        Speaker s = Speaker.builder()
                .id(speakerId)
                .video(video)
                .label("SPEAKER_01")
                .displayName("OldName")
                .createdAt(OffsetDateTime.parse("2026-05-13T09:00:00Z"))
                .build();
        when(speakerRepository.findById(speakerId)).thenReturn(Optional.of(s));
        when(speakerRepository.save(any(Speaker.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transcriptionSegmentRepository.countBySpeakerId(speakerId)).thenReturn(0L);

        mockMvc.perform(patch("/api/v1/speakers/{speakerId}", speakerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"   \"}"))
                .andExpect(status().isOk())
                // Empty/blank input clears the override → response carries null.
                .andExpect(jsonPath("$.displayName").doesNotExist());
    }

    @Test
    void renameReturns404WhenSpeakerMissing() throws Exception {
        UUID speakerId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        when(speakerRepository.findById(speakerId)).thenReturn(Optional.empty());

        mockMvc.perform(patch("/api/v1/speakers/{speakerId}", speakerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Whoever\"}"))
                .andExpect(status().isNotFound());
    }
}
