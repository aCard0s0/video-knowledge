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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    /**
     * Request-binding failures are client errors. They implement {@code ErrorResponse} but not
     * {@code ErrorResponseException}, so before the explicit handler they fell through to the
     * {@code Exception} catch-all and every one of them answered 500.
     */
    @Test
    void bindingFailuresReturn400() throws Exception {
        // missing required parameter
        mockMvc.perform(get("/api/v1/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad request"));

        // parameter-level constraint violation (@Max(50))
        mockMvc.perform(get("/api/v1/search").param("query", "q").param("limit", "999"))
                .andExpect(status().isBadRequest());

        // value that will not convert to the declared type
        mockMvc.perform(get("/api/v1/search").param("query", "q").param("limit", "abc"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Same gap as the binding failures above, one family over: these implement {@code ErrorResponse}
     * without extending {@code ErrorResponseException}, so they answered 500 too. 405 must carry
     * {@code Allow} per RFC 9110, which is why the handler copies the exception's headers.
     */
    @Test
    void protocolMismatchesKeepTheirOwnStatus() throws Exception {
        mockMvc.perform(post("/api/v1/search").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"));

        mockMvc.perform(get("/api/v1/search").param("query", "q").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable());
    }
}

