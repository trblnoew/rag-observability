package com.portfolio.ragobservability.document.chunk;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "rag.chunking")
public record ChunkingProperties(
        @Min(1) int chunkSizeTokens,
        @Min(1) @Max(10_000) int maxChunksPerDocument) {
}
