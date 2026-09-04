package com.portfolio.ragobservability.document.embedding;

import java.util.List;

import org.springframework.ai.embedding.EmbeddingModel;

public class OpenAiEmbeddingGateway implements EmbeddingGateway {

    private final EmbeddingModel embeddingModel;
    private final String model;
    private final int dimensions;

    public OpenAiEmbeddingGateway(
            EmbeddingModel embeddingModel,
            String model,
            int dimensions) {
        this.embeddingModel = embeddingModel;
        this.model = model;
        this.dimensions = dimensions;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public List<float[]> embed(List<String> inputs) {
        try {
            return embeddingModel.embed(List.copyOf(inputs));
        } catch (RuntimeException exception) {
            throw new EmbeddingProviderException(exception);
        }
    }
}
