package com.portfolio.ragobservability.retrieval;

import jakarta.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag.retrieval")
public class RetrievalProperties {

    public static final int MIN_TOP_K = 1;
    public static final int MAX_TOP_K = 20;
    public static final int MAX_QUERY_LENGTH = 2_000;

    private int topK = 5;
    private double minimumSimilarity = 0.0;

    @PostConstruct
    void validate() {
        if (topK < MIN_TOP_K || topK > MAX_TOP_K) {
            throw new IllegalStateException("rag.retrieval.top-k must be between 1 and 20");
        }
        if (!Double.isFinite(minimumSimilarity)
                || minimumSimilarity < -1.0
                || minimumSimilarity > 1.0) {
            throw new IllegalStateException(
                    "rag.retrieval.minimum-similarity must be a finite value between -1.0 and 1.0");
        }
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public double getMinimumSimilarity() {
        return minimumSimilarity;
    }

    public void setMinimumSimilarity(double minimumSimilarity) {
        this.minimumSimilarity = minimumSimilarity;
    }
}
