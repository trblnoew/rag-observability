package com.portfolio.ragobservability.document.embedding;

import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class EmbeddingVectorValidator {

    public List<float[]> validateResults(
            List<float[]> vectors,
            int expectedCount,
            int expectedDimensions) {
        if (vectors == null || vectors.size() != expectedCount) {
            throw new EmbeddingValidationException(
                    "Embedding result count does not match input count");
        }
        for (float[] vector : vectors) {
            validateVector(vector, expectedDimensions);
        }
        return vectors;
    }

    public float[] validateVector(float[] vector, int expectedDimensions) {
        if (vector == null
                || vector.length != expectedDimensions
                || vector.length != EmbeddingProperties.DATABASE_DIMENSIONS) {
            throw new EmbeddingValidationException(
                    "Embedding vector dimensions do not match the configured dimensions");
        }
        boolean hasNonZeroValue = false;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new EmbeddingValidationException(
                        "Embedding vector contains a non-finite value");
            }
            hasNonZeroValue |= value != 0.0f;
        }
        if (!hasNonZeroValue) {
            throw new EmbeddingValidationException(
                    "Embedding vector must not be an all-zero vector");
        }
        return vector;
    }
}
