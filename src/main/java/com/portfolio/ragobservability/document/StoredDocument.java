package com.portfolio.ragobservability.document;

import java.time.Instant;

import com.portfolio.ragobservability.document.embedding.EmbeddingStatus;

public record StoredDocument(
        long id,
        String title,
        String originalFilename,
        DocumentSourceType sourceType,
        String contentSha256,
        DocumentStatus status,
        String errorCode,
        String errorMessage,
        int retryCount,
        EmbeddingStatus embeddingStatus,
        String embeddingErrorCode,
        String embeddingErrorMessage,
        int embeddingRetryCount,
        String rawContent,
        Instant createdAt,
        Instant updatedAt) {

    public StoredDocument(
            long id,
            String title,
            String originalFilename,
            DocumentSourceType sourceType,
            String contentSha256,
            DocumentStatus status,
            String errorCode,
            String errorMessage,
            int retryCount,
            String rawContent,
            Instant createdAt,
            Instant updatedAt) {
        this(
                id,
                title,
                originalFilename,
                sourceType,
                contentSha256,
                status,
                errorCode,
                errorMessage,
                retryCount,
                EmbeddingStatus.NOT_STARTED,
                null,
                null,
                0,
                rawContent,
                createdAt,
                updatedAt);
    }
}
