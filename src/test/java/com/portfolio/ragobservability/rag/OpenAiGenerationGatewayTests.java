package com.portfolio.ragobservability.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class OpenAiGenerationGatewayTests {

    @Test
    void mapsMessagesAnswerAndTokenUsage() {
        ChatModel model = mock(ChatModel.class);
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("answer [S1]"))),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(120, 30, 150))
                        .build());
        when(model.call(any(Prompt.class))).thenReturn(response);
        OpenAiGenerationGateway gateway = new OpenAiGenerationGateway(model, "test-model");

        GenerationResult result = gateway.generate(new GenerationPrompt("system", "user"));

        assertThat(result.answer()).isEqualTo("answer [S1]");
        assertThat(result.inputTokens()).isEqualTo(120);
        assertThat(result.outputTokens()).isEqualTo(30);
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        org.mockito.Mockito.verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getSystemMessage().getText()).isEqualTo("system");
        assertThat(prompt.getValue().getUserMessage().getText()).isEqualTo("user");
    }

    @Test
    void wrapsProviderFailures() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("secret provider detail"));
        OpenAiGenerationGateway gateway = new OpenAiGenerationGateway(model, "test-model");

        assertThatThrownBy(() -> gateway.generate(new GenerationPrompt("system", "user")))
                .isInstanceOf(GenerationProviderException.class);
    }

    @Test
    void preservesEmptyProviderOutputForServiceValidation() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of()));
        OpenAiGenerationGateway gateway = new OpenAiGenerationGateway(model, "test-model");

        GenerationResult result = gateway.generate(new GenerationPrompt("system", "user"));

        assertThat(result.answer()).isNull();
        assertThat(result.inputTokens()).isZero();
        assertThat(result.outputTokens()).isZero();
    }
}
