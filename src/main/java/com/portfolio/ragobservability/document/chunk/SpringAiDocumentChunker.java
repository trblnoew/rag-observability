package com.portfolio.ragobservability.document.chunk;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

@Component
public class SpringAiDocumentChunker implements DocumentChunker {

    private final TokenTextSplitter tokenTextSplitter;
    private final TokenCountEstimator tokenCountEstimator;

    public SpringAiDocumentChunker(
            TokenTextSplitter tokenTextSplitter,
            TokenCountEstimator tokenCountEstimator) {
        this.tokenTextSplitter = tokenTextSplitter;
        this.tokenCountEstimator = tokenCountEstimator;
    }

    @Override
    public List<ChunkDraft> split(String content) {
        return tokenTextSplitter.split(new Document(content)).stream()
                .map(Document::getText)
                .filter(text -> text != null && !text.isBlank())
                .map(text -> new ChunkDraft(text, tokenCountEstimator.estimate(text)))
                .toList();
    }
}
