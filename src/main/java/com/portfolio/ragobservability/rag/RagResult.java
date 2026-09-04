package com.portfolio.ragobservability.rag;

import java.util.List;

public record RagResult(
        String question,
        String answer,
        List<RagSource> sources,
        int topK,
        double minimumSimilarity,
        String embeddingProvider,
        String embeddingModel,
        int dimensions,
        String generationProvider,
        String generationModel,
        boolean generationCalled,
        Integer inputTokens,
        Integer outputTokens) {

    public RagResult {
        sources = List.copyOf(sources);
    }
}
