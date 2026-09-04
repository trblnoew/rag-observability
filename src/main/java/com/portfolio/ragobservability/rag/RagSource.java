package com.portfolio.ragobservability.rag;

public record RagSource(
        String sourceId,
        long chunkId,
        long documentId,
        String documentTitle,
        int chunkIndex,
        double similarity,
        String excerpt) {
}
