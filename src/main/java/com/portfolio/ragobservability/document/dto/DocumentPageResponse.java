package com.portfolio.ragobservability.document.dto;

import java.util.List;

public record DocumentPageResponse(
        List<DocumentSummaryResponse> items,
        int page,
        int size,
        long totalElements,
        long totalPages) {

    public DocumentPageResponse {
        items = List.copyOf(items);
    }
}
