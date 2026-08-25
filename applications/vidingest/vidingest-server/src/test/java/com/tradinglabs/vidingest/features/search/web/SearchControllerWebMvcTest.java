package com.tradinglabs.vidingest.features.search.web;

import com.tradinglabs.vidingest.commons.VidingestApiExceptionHandler;
import com.tradinglabs.vidingest.search.service.SearchChunkResultMapper;
import com.tradinglabs.vidingest.search.service.SemanticSearchService;
import com.tradinglabs.vidingest.search.exceptions.SemanticSearchUnavailableException;
import com.tradinglabs.vidingest.search.controller.SearchController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SearchController.class)
@Import({VidingestApiExceptionHandler.class, SearchChunkResultMapper.class})
class SearchControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SemanticSearchService semanticSearchService;

    @Test
    void searchReturns409WhenSemanticSearchDisabled() throws Exception {
        when(semanticSearchService.searchSimilarChunks("q", 5))
                .thenThrow(new SemanticSearchUnavailableException("Semantic search is disabled."));

        mockMvc.perform(get("/api/v1/search")
                        .param("query", "q")
                        .param("limit", "5"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"));
    }
}

