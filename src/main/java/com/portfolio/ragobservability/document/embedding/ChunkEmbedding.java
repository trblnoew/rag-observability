package com.portfolio.ragobservability.document.embedding;

public record ChunkEmbedding(long chunkId, float[] vector) {

    public ChunkEmbedding {
        vector = vector.clone();
    }

    @Override
    public float[] vector() {
        return vector.clone();
    }
}
