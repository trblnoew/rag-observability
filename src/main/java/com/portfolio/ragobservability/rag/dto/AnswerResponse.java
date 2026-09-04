package com.portfolio.ragobservability.rag.dto;

import java.util.List;

import com.portfolio.ragobservability.rag.RagResult;

public record AnswerResponse(
        String question,
        String answer,
        List<AnswerSourceResponse> sources,
        AnswerMeta meta) {

    public AnswerResponse {
        sources = List.copyOf(sources);
    }

    public static AnswerResponse from(RagResult result) {
        List<AnswerSourceResponse> sources = result.sources().stream()
                .map(AnswerSourceResponse::from)
                .toList();
        return new AnswerResponse(
                result.question(),
                result.answer(),
                sources,
                new AnswerMeta(
                        result.topK(),
                        result.minimumSimilarity(),
                        sources.size(),
                        result.embeddingProvider(),
                        result.embeddingModel(),
                        result.dimensions(),
                        result.generationProvider(),
                        result.generationModel(),
                        result.generationCalled(),
                        result.inputTokens(),
                        result.outputTokens()));
    }
}
