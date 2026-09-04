package com.portfolio.ragobservability.rag.dto;

import com.portfolio.ragobservability.rag.RagSource;

public record AnswerSourceResponse(
        String sourceId,
        long chunkId,
        long documentId,
        String documentTitle,
        int chunkIndex,
        double similarity,
        String excerpt) {

    static AnswerSourceResponse from(RagSource source) {
        return new AnswerSourceResponse(
                source.sourceId(),
                source.chunkId(),
                source.documentId(),
                source.documentTitle(),
                source.chunkIndex(),
                source.similarity(),
                source.excerpt());
    }
}
