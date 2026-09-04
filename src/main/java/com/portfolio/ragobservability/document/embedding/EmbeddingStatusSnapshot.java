package com.portfolio.ragobservability.document.embedding;

public record EmbeddingStatusSnapshot(
        long documentId,
        EmbeddingStatus status,
        int totalChunkCount,
        int embeddedChunkCount,
        String provider,
        String model,
        Integer dimensions,
        int retryCount,
        String errorCode,
        String errorMessage) {
}
