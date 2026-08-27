package com.tradinglabs.vidingest.core.fusion.controller;

import com.tradinglabs.vidingest.commons.VidingestApiExceptionHandler;
import com.tradinglabs.vidingest.core.frames.domain.SamplingReason;
import com.tradinglabs.vidingest.core.frames.domain.VideoFrame;
import com.tradinglabs.vidingest.core.fusion.domain.MultimodalSegment;
import com.tradinglabs.vidingest.core.fusion.mapper.MultimodalSegmentMapper;
import com.tradinglabs.vidingest.core.fusion.repo.MultimodalSegmentRepository;
import com.tradinglabs.vidingest.core.ocr.domain.OcrResult;
import com.tradinglabs.vidingest.core.ocr.mapper.OcrResultMapper;
import com.tradinglabs.vidingest.core.fusion.service.MultimodalTimelineQueryService;
import com.tradinglabs.vidingest.core.ocr.repo.OcrResultRepository;
import com.tradinglabs.vidingest.core.ocr.service.OcrQueryService;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import com.tradinglabs.vidingest.videos.service.VideoQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvc tests for {@link VideoMultimodalArtifactsController}. Covers the multimodal timeline
 * and the OCR-grouped-by-frame endpoint, through the real query services — the controller is a
 * pass-through, so mocking them would leave these assertions testing nothing but Jackson.
 *
 * <p>The from/to window itself is a JPQL predicate now, so a mocked repository cannot exercise
 * it. It is covered against a real database in
 * {@code MultimodalTimelineWindowIntegrationTest}; what is asserted here is that the request
 * parameters reach the query unchanged.
 */
@WebMvcTest(controllers = VideoMultimodalArtifactsController.class)
@Import({VidingestApiExceptionHandler.class, MultimodalSegmentMapper.class, OcrResultMapper.class,
        MultimodalTimelineQueryService.class, OcrQueryService.class, VideoQueryService.class})
class VideoMultimodalArtifactsControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MultimodalSegmentRepository multimodalSegmentRepository;
    @MockitoBean
    private OcrResultRepository ocrResultRepository;
    @MockitoBean
    private VideoRepository videoRepository;

    @Test
    void multimodalTimelineReturnsAllSegmentsWhenNoBoundsGiven() throws Exception {
        UUID videoId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Video v = new Video();
        v.setId(videoId);
        when(videoRepository.existsById(videoId)).thenReturn(true);
        when(multimodalSegmentRepository.findPageByVideoIdWindowedOrderBySegmentIndex(
                eq(videoId), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        segment(v, 0, 0.0, 30.0, "spoken 0", "ocr 0"),
                        segment(v, 1, 30.0, 60.0, "spoken 1", null)
                )));

        mockMvc.perform(get("/api/v1/videos/{videoId}/multimodal-timeline", videoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].segmentIndex").value(0))
                .andExpect(jsonPath("$[0].transcriptText").value("spoken 0"))
                .andExpect(jsonPath("$[0].ocrText").value("ocr 0"))
                .andExpect(jsonPath("$[1].segmentIndex").value(1));
    }

    @Test
    void multimodalTimelinePassesTheRequestedWindowToTheQuery() throws Exception {
        UUID videoId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Video v = new Video();
        v.setId(videoId);
        when(videoRepository.existsById(videoId)).thenReturn(true);
        when(multimodalSegmentRepository.findPageByVideoIdWindowedOrderBySegmentIndex(
                eq(videoId), eq(25.0), eq(65.0), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(segment(v, 1, 30.0, 60.0, "middle", null))));

        mockMvc.perform(get("/api/v1/videos/{videoId}/multimodal-timeline", videoId)
                        .param("fromSeconds", "25")
                        .param("toSeconds", "65"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].transcriptText").value("middle"));

        verify(multimodalSegmentRepository).findPageByVideoIdWindowedOrderBySegmentIndex(
                eq(videoId), eq(25.0), eq(65.0), any(Pageable.class));
    }

    @Test
    void multimodalTimelineReturns404WhenVideoMissing() throws Exception {
        UUID videoId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        when(videoRepository.existsById(videoId)).thenReturn(false);

        mockMvc.perform(get("/api/v1/videos/{videoId}/multimodal-timeline", videoId))
                .andExpect(status().isNotFound());
    }

    @Test
    void ocrResultsReturnsFrameGroups() throws Exception {
        UUID videoId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        Video v = new Video();
        v.setId(videoId);
        when(videoRepository.existsById(videoId)).thenReturn(true);

        UUID frameId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        VideoFrame frame = VideoFrame.builder()
                .id(frameId)
                .video(v)
                .timestampSeconds(10.0)
                .filePath("/tmp/0001.jpg")
                .samplingReason(SamplingReason.INTERVAL)
                .frameIndex(0)
                .build();
        OcrResult line1 = OcrResult.builder()
                .id(UUID.fromString("66666666-6666-6666-6666-666666666666"))
                .frame(frame)
                .text("Hello world")
                .confidence(0.94f)
                .language("en")
                .createdAt(OffsetDateTime.parse("2026-05-13T11:00:00Z"))
                .build();
        when(ocrResultRepository.findByVideoIdOrderByFrameTimestamp(videoId))
                .thenReturn(List.of(line1));

        mockMvc.perform(get("/api/v1/videos/{videoId}/ocr", videoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].frameId").value(frameId.toString()))
                .andExpect(jsonPath("$[0].timestampSeconds").value(10.0))
                .andExpect(jsonPath("$[0].framePath").value("/tmp/0001.jpg"))
                .andExpect(jsonPath("$[0].lines[0].text").value("Hello world"))
                .andExpect(jsonPath("$[0].lines[0].confidence").value(0.94));
    }

    @Test
    void ocrResultsReturns404WhenVideoMissing() throws Exception {
        UUID videoId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        when(videoRepository.existsById(videoId)).thenReturn(false);

        mockMvc.perform(get("/api/v1/videos/{videoId}/ocr", videoId))
                .andExpect(status().isNotFound());
    }

    private static MultimodalSegment segment(Video v, int idx, double start, double end,
                                             String transcript, String ocr) {
        return MultimodalSegment.builder()
                .id(UUID.randomUUID())
                .video(v)
                .segmentIndex(idx)
                .startSeconds(start)
                .endSeconds(end)
                .transcriptText(transcript)
                .ocrText(ocr)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }
}
