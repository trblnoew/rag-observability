package com.portfolio.ragobservability.document.embedding;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class EmbeddingPropertiesTests {

    @Test
    void usesSafeTimeoutDefaults() {
        EmbeddingProperties properties = new EmbeddingProperties();

        assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void openAiRequiresApiKeyAndDatabaseDimensions() {
        EmbeddingProperties missingKey = new EmbeddingProperties();
        missingKey.setProvider("openai");

        assertThatThrownBy(missingKey::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENAI_API_KEY");

        EmbeddingProperties wrongDimensions = new EmbeddingProperties();
        wrongDimensions.setProvider("openai");
        wrongDimensions.setApiKey("test-key");
        wrongDimensions.setDimensions(768);

        assertThatThrownBy(wrongDimensions::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1536");
    }

    @Test
    void rejectsUnknownProviderWithoutPrintingApiKey() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setProvider("unknown");
        properties.setApiKey("secret-value");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("secret-value");
    }

    @Test
    void rejectsZeroAndNegativeTimeouts() {
        EmbeddingProperties zeroConnectTimeout = new EmbeddingProperties();
        zeroConnectTimeout.setConnectTimeout(Duration.ZERO);
        assertThatThrownBy(zeroConnectTimeout::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connect-timeout");

        EmbeddingProperties negativeReadTimeout = new EmbeddingProperties();
        negativeReadTimeout.setReadTimeout(Duration.ofSeconds(-1));
        assertThatThrownBy(negativeReadTimeout::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read-timeout");
    }
}
