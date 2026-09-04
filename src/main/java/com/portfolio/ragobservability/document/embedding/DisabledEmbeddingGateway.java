package com.portfolio.ragobservability.document.embedding;

import java.util.List;

public class DisabledEmbeddingGateway implements EmbeddingGateway {

    private final int dimensions;

    public DisabledEmbeddingGateway(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public String provider() {
        return "none";
    }

    @Override
    public String model() {
        return "none";
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public List<float[]> embed(List<String> inputs) {
        throw new IllegalStateException("Embedding provider is disabled");
    }
}
