package com.portfolio.ragobservability.rag;

import jakarta.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag.answer")
public class AnswerProperties {

    public static final int MAX_SOURCE_EXCERPT_LENGTH = 10_000;

    private double minimumSimilarity = 0.5;
    private int sourceExcerptLength = 500;

    @PostConstruct
    void validate() {
        if (!Double.isFinite(minimumSimilarity)
                || minimumSimilarity < -1.0
                || minimumSimilarity > 1.0) {
            throw new IllegalStateException(
                    "rag.answer.minimum-similarity must be a finite value between -1.0 and 1.0");
        }
        if (sourceExcerptLength < 1 || sourceExcerptLength > MAX_SOURCE_EXCERPT_LENGTH) {
            throw new IllegalStateException(
                    "rag.answer.source-excerpt-length must be between 1 and 10000");
        }
    }

    public double getMinimumSimilarity() {
        return minimumSimilarity;
    }

    public void setMinimumSimilarity(double minimumSimilarity) {
        this.minimumSimilarity = minimumSimilarity;
    }

    public int getSourceExcerptLength() {
        return sourceExcerptLength;
    }

    public void setSourceExcerptLength(int sourceExcerptLength) {
        this.sourceExcerptLength = sourceExcerptLength;
    }
}
