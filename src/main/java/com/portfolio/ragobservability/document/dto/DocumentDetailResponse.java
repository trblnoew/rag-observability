package com.portfolio.ragobservability.document.dto;

import java.time.Instant;

import com.portfolio.ragobservability.document.DocumentSourceType;
import com.portfolio.ragobservability.document.DocumentStatus;
import com.portfolio.ragobservability.document.embedding.EmbeddingStatus;

public record DocumentDetailResponse(
        long id,
        String title,
        String originalFilename,
        DocumentSourceType sourceType,
        DocumentStatus status,
        int retryCount,
        EmbeddingStatus embeddingStatus,
        int embeddingRetryCount,
        String content,
        String contentSha256,
        String errorCode,
        String errorMessage,
        String embeddingErrorCode,
        String embeddingErrorMessage,
        Instant createdAt,
        Instant updatedAt) {
}
