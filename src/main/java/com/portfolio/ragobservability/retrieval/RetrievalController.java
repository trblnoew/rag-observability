package com.portfolio.ragobservability.retrieval;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.portfolio.ragobservability.retrieval.dto.SearchRequest;
import com.portfolio.ragobservability.retrieval.dto.SearchResponse;

@Validated
@RestController
@RequestMapping("/api/v1/search")
public class RetrievalController {

    private final RetrievalService retrievalService;

    public RetrievalController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public SearchResponse search(@Valid @RequestBody SearchRequest request) {
        return SearchResponse.from(retrievalService.search(
                request.query(),
                request.topK(),
                request.minimumSimilarity()));
    }
}
