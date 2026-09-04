package com.portfolio.ragobservability.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class OpenAiGenerationGatewayLiveTests {

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_OPENAI_INTEGRATION_TEST", matches = "(?i)true")
    @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    void createsRealOpenAiAnswerOnlyWhenExplicitlyEnabled() {
        GenerationProperties properties = new GenerationProperties();
        properties.setProvider("openai");
        properties.setApiKey(System.getenv("OPENAI_API_KEY"));
        properties.validate();

        GenerationGateway gateway = new GenerationConfiguration()
                .openAiGenerationGateway(properties);
        GenerationResult result = gateway.generate(new GenerationPrompt(
                "Answer only from the supplied context and cite [S1].",
                "QUESTION: What is the marker?\nCONTEXT: [S1] The marker is blue."));

        assertThat(result.answer()).isNotBlank().contains("[S1]");
    }
}
