package com.portfolio.ragobservability.document.embedding;

import java.time.Duration;
import java.util.Locale;

import jakarta.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "rag.embedding")
public class EmbeddingProperties {

    public static final int DATABASE_DIMENSIONS = 1536;

    private String provider = "none";
    private String apiKey = "";
    private String baseUrl = "https://api.openai.com";
    private String model = "text-embedding-3-small";
    private int dimensions = DATABASE_DIMENSIONS;
    private int batchSize = 32;
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(30);

    @PostConstruct
    void validate() {
        provider = provider == null ? "none" : provider.trim().toLowerCase(Locale.ROOT);
        if (!provider.equals("none") && !provider.equals("openai")) {
            throw new IllegalStateException("rag.embedding.provider must be 'none' or 'openai'");
        }
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalStateException("rag.embedding.batch-size must be between 1 and 500");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalStateException("rag.embedding.connect-timeout must be positive");
        }
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalStateException("rag.embedding.read-timeout must be positive");
        }
        if (provider.equals("openai")) {
            if (!StringUtils.hasText(apiKey)) {
                throw new IllegalStateException(
                        "OPENAI_API_KEY must be configured when embedding provider is openai");
            }
            if (!StringUtils.hasText(model)) {
                throw new IllegalStateException("rag.embedding.model must not be blank");
            }
            if (!StringUtils.hasText(baseUrl)) {
                throw new IllegalStateException("rag.embedding.base-url must not be blank");
            }
            if (dimensions != DATABASE_DIMENSIONS) {
                throw new IllegalStateException(
                        "Embedding dimensions must be %d to match document_chunk.embedding"
                                .formatted(DATABASE_DIMENSIONS));
            }
        }
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
