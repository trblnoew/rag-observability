package com.portfolio.ragobservability.document.chunk.dto;

import com.portfolio.ragobservability.document.DocumentStatus;

public record DocumentProcessingResponse(
        long documentId,
        DocumentStatus status,
        int chunkCount,
        int retryCount) {
}
