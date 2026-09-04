package com.portfolio.ragobservability.retrieval.dto;

import java.util.List;

import com.portfolio.ragobservability.retrieval.RetrievalResult;
import com.portfolio.ragobservability.retrieval.SearchHit;

public record SearchResponse(
        String query,
        List<SearchHit> results,
        SearchMeta meta) {

    public SearchResponse {
        results = List.copyOf(results);
    }

    public static SearchResponse from(RetrievalResult result) {
        return new SearchResponse(
                result.query(),
                result.results(),
                new SearchMeta(
                        result.topK(),
                        result.minimumSimilarity(),
                        result.results().size(),
                        result.embeddingProvider(),
                        result.embeddingModel(),
                        result.dimensions()));
    }
}
