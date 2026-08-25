package com.tradinglabs.vidingest.core.knowledge.controller;

import com.tradinglabs.vidingest.api.knowledge.KnowledgeUnitType;
import com.tradinglabs.vidingest.api.knowledge.SearchKnowledgeHit;
import com.tradinglabs.vidingest.commons.VidingestApiExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinglabs.vidingest.core.knowledge.domain.KnowledgeUnit;
import com.tradinglabs.vidingest.core.knowledge.mapper.KnowledgeUnitMapper;
import com.tradinglabs.vidingest.core.knowledge.repo.KnowledgeUnitRepository;
import com.tradinglabs.vidingest.core.knowledge.repo.KnowledgeUnitRepository.KnowledgeUnitView;
import com.tradinglabs.vidingest.core.knowledge.service.KnowledgeExtractionService;
import com.tradinglabs.vidingest.core.knowledge.service.KnowledgeQueryService;
import com.tradinglabs.vidingest.core.knowledge.service.SemanticKnowledgeSearchService;
import com.tradinglabs.vidingest.search.exceptions.SemanticSearchUnavailableException;
import com.tradinglabs.vidingest.videos.domain.Video;
import com.tradinglabs.vidingest.videos.repo.VideoRepository;
import com.tradinglabs.vidingest.videos.service.VideoQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvc tests for the M8 knowledge endpoints. Mirrors {@code SearchControllerWebMvcTest}
 * in style — mocks the services and asserts JSON wiring + status codes.
 */
@WebMvcTest(controllers = KnowledgeController.class)
@Import({VidingestApiExceptionHandler.class, KnowledgeUnitMapper.class,
        KnowledgeQueryService.class, VideoQueryService.class})
class KnowledgeControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SemanticKnowledgeSearchService searchService;
    @MockitoBean
    private KnowledgeExtractionService extractionService;
    @MockitoBean
    private KnowledgeUnitRepository knowledgeUnitRepository;
    @MockitoBean
    private VideoRepository videoRepository;
    @MockitoBean
    private ObjectMapper objectMapper;

    @Test
    void searchReturnsHitsAsJson() throws Exception {
        when(searchService.searchKnowledge(eq("apple"), eq(KnowledgeUnitType.ENTITY), eq(10)))
                .thenReturn(List.of(new SearchKnowledgeHit(
                        "11111111-1111-1111-1111-111111111111",
                        "22222222-2222-2222-2222-222222222222",
                        KnowledgeUnitType.ENTITY,
                        "Apple Inc.",
                        "Tech company headquartered in Cupertino",
                        "Apple Keynote 2026",
                        "Apple",
                        0.0,
                        30.0
                )));

        mockMvc.perform(get("/api/v1/knowledge/search")
                        .param("query", "apple")
                        .param("type", "ENTITY")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].knowledgeUnitId").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$[0].type").value("ENTITY"))
                .andExpect(jsonPath("$[0].title").value("Apple Inc."));
    }

    @Test
    void searchForwardsAnAbsentLimitAsNull() throws Exception {
        // The default and the cap live in SemanticKnowledgeSearchService now; the controller
        // used to restate both, so the 50 in the clamp existed twice.
        when(searchService.searchKnowledge(eq("pear"), isNull(), isNull())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/knowledge/search").param("query", "pear"))
                .andExpect(status().isOk());
    }

    @Test
    void searchReturns409WhenSemanticSearchDisabled() throws Exception {
        when(searchService.searchKnowledge(eq("anything"), isNull(), eq(10)))
                .thenThrow(new SemanticSearchUnavailableException("Semantic search is disabled."));

        mockMvc.perform(get("/api/v1/knowledge/search")
                        .param("query", "anything")
                        .param("limit", "10"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"));
    }

    @Test
    void listForVideoReturnsKnowledgeUnitsAsJson() throws Exception {
        UUID videoId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        when(videoRepository.existsById(videoId)).thenReturn(true);

        KnowledgeUnitView view = new KnowledgeUnitView() {
            @Override
            public String getId() {
                return "44444444-4444-4444-4444-444444444444";
            }

            @Override
            public String getVideoId() {
                return videoId.toString();
            }

            @Override
            public String getType() {
                return "SUMMARY";
            }

            @Override
            public String getTitle() {
                return "Overview";
            }

            @Override
            public String getContent() {
                return "This is the body.";
            }

            @Override
            public String getMetadataJson() {
                return null;
            }

            @Override
            public Double getStartSeconds() {
                return null;
            }

            @Override
            public Double getEndSeconds() {
                return null;
            }

            @Override
            public String getCreatedAt() {
                return LocalDateTime.parse("2026-05-13T10:00:00").toString();
            }
        };

        when(knowledgeUnitRepository.findViewsByVideoId(videoId, null))
                .thenReturn(List.of(view));

        mockMvc.perform(get("/api/v1/videos/{videoId}/knowledge", videoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("44444444-4444-4444-4444-444444444444"))
                .andExpect(jsonPath("$[0].type").value("SUMMARY"))
                .andExpect(jsonPath("$[0].title").value("Overview"));
    }

    @Test
    void listForVideoReturns404WhenVideoMissing() throws Exception {
        UUID videoId = UUID.fromString("00000000-0000-0000-0000-000000000000");
        when(videoRepository.existsById(videoId)).thenReturn(false);

        mockMvc.perform(get("/api/v1/videos/{videoId}/knowledge", videoId))
                .andExpect(status().isNotFound());
    }

    @Test
    void regenerateRunsExtractionAndReturnsResult() throws Exception {
        UUID videoId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        Video video = new Video();
        video.setId(videoId);
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));
        when(extractionService.extractKnowledge(video)).thenReturn(7);

        mockMvc.perform(post("/api/v1/videos/{videoId}/knowledge/regenerate", videoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoId").value(videoId.toString()))
                .andExpect(jsonPath("$.knowledgeUnitCount").value(7));
    }
}
