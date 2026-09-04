package com.portfolio.ragobservability.document.embedding;

import java.util.List;

public interface EmbeddingGateway {

    boolean isEnabled();

    String provider();

    String model();

    int dimensions();

    List<float[]> embed(List<String> inputs);
}
