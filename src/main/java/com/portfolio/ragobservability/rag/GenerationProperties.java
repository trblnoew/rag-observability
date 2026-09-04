package com.portfolio.ragobservability.rag;

import java.time.Duration;
import java.util.Locale;

import jakarta.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "rag.generation")
public class GenerationProperties {

    private String provider = "none";
    private String apiKey = "";
    private String baseUrl = "https://api.openai.com";
    private String model = "gpt-4.1-mini";
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(60);
    private int maxOutputTokens = 600;
    private double temperature = 0.0;

    @PostConstruct
    void validate() {
        provider = provider == null ? "none" : provider.trim().toLowerCase(Locale.ROOT);
        if (!provider.equals("none") && !provider.equals("openai")) {
            throw new IllegalStateException("rag.generation.provider must be 'none' or 'openai'");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalStateException("rag.generation.connect-timeout must be positive");
        }
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalStateException("rag.generation.read-timeout must be positive");
        }
        if (maxOutputTokens < 1 || maxOutputTokens > 32_768) {
            throw new IllegalStateException(
                    "rag.generation.max-output-tokens must be between 1 and 32768");
        }
        if (!Double.isFinite(temperature) || temperature < 0.0 || temperature > 2.0) {
            throw new IllegalStateException(
                    "rag.generation.temperature must be a finite value between 0.0 and 2.0");
        }
        if (provider.equals("openai")) {
            if (!StringUtils.hasText(apiKey)) {
                throw new IllegalStateException(
                        "OPENAI_API_KEY must be configured when generation provider is openai");
            }
            if (!StringUtils.hasText(model)) {
                throw new IllegalStateException("rag.generation.model must not be blank");
            }
            if (!StringUtils.hasText(baseUrl)) {
                throw new IllegalStateException("rag.generation.base-url must not be blank");
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

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }
}
