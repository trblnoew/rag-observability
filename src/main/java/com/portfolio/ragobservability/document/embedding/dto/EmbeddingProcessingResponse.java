package com.portfolio.ragobservability.document.embedding.dto;

import com.portfolio.ragobservability.document.embedding.EmbeddingStatus;

public record EmbeddingProcessingResponse(
        long documentId,
        EmbeddingStatus embeddingStatus,
        int embeddedChunkCount,
        String provider,
        String model,
        int dimensions,
        int retryCount) {
}
