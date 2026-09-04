package com.portfolio.ragobservability.rag.dto;

public record AnswerMeta(
        int topK,
        double minimumSimilarity,
        int sourceCount,
        String embeddingProvider,
        String embeddingModel,
        int dimensions,
        String generationProvider,
        String generationModel,
        boolean generationCalled,
        Integer inputTokens,
        Integer outputTokens) {
}
