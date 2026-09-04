package com.portfolio.ragobservability.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class GenerationPropertiesTests {

    @Test
    void usesSafeDefaults() {
        GenerationProperties properties = new GenerationProperties();
        properties.validate();

        assertThat(properties.getProvider()).isEqualTo("none");
        assertThat(properties.getModel()).isEqualTo("gpt-4.1-mini");
        assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.getMaxOutputTokens()).isEqualTo(600);
        assertThat(properties.getTemperature()).isZero();
    }

    @Test
    void validatesOpenAiConfiguration() {
        GenerationProperties properties = openAiProperties();
        properties.validate();
        assertThat(properties.getProvider()).isEqualTo("openai");

        properties.setApiKey(" ");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void rejectsInvalidTimeoutTokensTemperatureAndProvider() {
        GenerationProperties properties = new GenerationProperties();
        properties.setConnectTimeout(Duration.ZERO);
        assertInvalid(properties, "connect-timeout");

        properties = new GenerationProperties();
        properties.setReadTimeout(Duration.ofSeconds(-1));
        assertInvalid(properties, "read-timeout");

        properties = new GenerationProperties();
        properties.setMaxOutputTokens(0);
        assertInvalid(properties, "max-output-tokens");

        properties = new GenerationProperties();
        properties.setTemperature(Double.NaN);
        assertInvalid(properties, "temperature");

        properties = new GenerationProperties();
        properties.setProvider("other");
        assertInvalid(properties, "provider");
    }

    private static GenerationProperties openAiProperties() {
        GenerationProperties properties = new GenerationProperties();
        properties.setProvider(" OPENAI ");
        properties.setApiKey("test-key");
        return properties;
    }

    private static void assertInvalid(GenerationProperties properties, String message) {
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(message);
    }
}
