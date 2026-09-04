package com.portfolio.ragobservability.document.chunk.dto;

import java.time.Instant;

public record DocumentChunkResponse(
        long id,
        int chunkIndex,
        String content,
        int tokenCount,
        boolean embedded,
        String embeddingProvider,
        String embeddingModel,
        Instant createdAt) {
}
