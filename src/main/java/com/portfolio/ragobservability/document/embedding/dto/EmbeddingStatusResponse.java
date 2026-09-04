package com.portfolio.ragobservability.document.embedding.dto;

import com.portfolio.ragobservability.document.embedding.EmbeddingStatus;

public record EmbeddingStatusResponse(
        long documentId,
        EmbeddingStatus embeddingStatus,
        int totalChunkCount,
        int embeddedChunkCount,
        String provider,
        String model,
        Integer dimensions,
        int retryCount,
        String errorCode,
        String errorMessage) {
}
