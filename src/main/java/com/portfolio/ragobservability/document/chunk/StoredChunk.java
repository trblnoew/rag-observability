package com.portfolio.ragobservability.document.chunk;

import java.time.Instant;

public record StoredChunk(
        long id,
        long documentId,
        int chunkIndex,
        String content,
        int tokenCount,
        boolean embedded,
        String embeddingProvider,
        String embeddingModel,
        Instant createdAt) {
}
