package com.portfolio.ragobservability.document.chunk.dto;

import java.util.List;

import com.portfolio.ragobservability.document.DocumentStatus;

public record DocumentChunksResponse(
        long documentId,
        DocumentStatus status,
        List<DocumentChunkResponse> items) {

    public DocumentChunksResponse {
        items = List.copyOf(items);
    }
}
