package com.portfolio.ragobservability.retrieval;

import java.util.List;

public record RetrievalResult(
        String query,
        List<SearchHit> results,
        int topK,
        double minimumSimilarity,
        String embeddingProvider,
        String embeddingModel,
        int dimensions) {

    public RetrievalResult {
        results = List.copyOf(results);
    }
}
