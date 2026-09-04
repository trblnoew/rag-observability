package com.portfolio.ragobservability.retrieval.dto;

public record SearchMeta(
        int topK,
        double minimumSimilarity,
        int resultCount,
        String embeddingProvider,
        String embeddingModel,
        int dimensions) {
}
