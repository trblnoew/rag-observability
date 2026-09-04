package com.portfolio.ragobservability.document.embedding;

public class EmbeddingProviderException extends RuntimeException {

    public EmbeddingProviderException(Throwable cause) {
        super("Embedding provider request failed", cause);
    }
}
