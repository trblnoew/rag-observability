package com.portfolio.ragobservability.rag;

public record GenerationResult(
        String answer,
        Integer inputTokens,
        Integer outputTokens) {
}
