package com.portfolio.ragobservability.document.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

class SpringAiDocumentChunkerTests {

    private final SpringAiDocumentChunker chunker = new SpringAiDocumentChunker(
            TokenTextSplitter.builder().withChunkSize(600).build(),
            new JTokkitTokenCountEstimator());

    @Test
    void keepsShortDocumentInOneChunkAndCountsTokens() {
        var chunks = chunker.split("Database timeout runbook. Check the connection pool first.");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().content()).contains("connection pool");
        assertThat(chunks.getFirst().tokenCount()).isPositive();
    }

    @Test
    void splitsLongDocumentInOrderWithoutDroppingSentinelSections() {
        String content = IntStream.range(0, 1_200)
                .mapToObj(index -> "SECTION_%04d check database connection pool. ".formatted(index))
                .collect(Collectors.joining());

        var chunks = chunker.split(content);
        String combined = chunks.stream().map(ChunkDraft::content).collect(Collectors.joining(" "));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(combined).contains("SECTION_0000", "SECTION_0600", "SECTION_1199");
        assertThat(combined.indexOf("SECTION_0000"))
                .isLessThan(combined.indexOf("SECTION_0600"));
        assertThat(combined.indexOf("SECTION_0600"))
                .isLessThan(combined.indexOf("SECTION_1199"));
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.tokenCount()).isPositive());
    }
}
