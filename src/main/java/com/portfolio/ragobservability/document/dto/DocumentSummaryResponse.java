package com.portfolio.ragobservability.document.dto;

import java.time.Instant;

import com.portfolio.ragobservability.document.DocumentSourceType;
import com.portfolio.ragobservability.document.DocumentStatus;
import com.portfolio.ragobservability.document.embedding.EmbeddingStatus;

public record DocumentSummaryResponse(
        long id,
        String title,
        String originalFilename,
        DocumentSourceType sourceType,
        DocumentStatus status,
        int retryCount,
        EmbeddingStatus embeddingStatus,
        Instant createdAt,
        Instant updatedAt) {
}
