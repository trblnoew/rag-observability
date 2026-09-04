package com.portfolio.ragobservability.retrieval;

public record SearchHit(
        long chunkId,
        long documentId,
        String documentTitle,
        int chunkIndex,
        String content,
        double similarity) {
}
