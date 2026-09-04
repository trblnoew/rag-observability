package com.portfolio.ragobservability.document.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class OpenAiEmbeddingGatewayLiveTests {

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_OPENAI_INTEGRATION_TEST", matches = "(?i)true")
    @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    void createsRealOpenAiEmbeddingOnlyWhenExplicitlyEnabled() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setProvider("openai");
        properties.setApiKey(System.getenv("OPENAI_API_KEY"));
        properties.validate();

        EmbeddingGateway gateway = new EmbeddingConfiguration().openAiEmbeddingGateway(properties);
        var result = gateway.embed(java.util.List.of("small live embedding smoke test"));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).hasSize(EmbeddingProperties.DATABASE_DIMENSIONS);
    }
}
