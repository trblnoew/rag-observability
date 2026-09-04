package com.portfolio.ragobservability.rag;

import java.util.List;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

public class OpenAiGenerationGateway implements GenerationGateway {

    private final ChatModel chatModel;
    private final String model;

    public OpenAiGenerationGateway(ChatModel chatModel, String model) {
        this.chatModel = chatModel;
        this.model = model;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public GenerationResult generate(GenerationPrompt prompt) {
        try {
            ChatResponse response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(prompt.systemPrompt()),
                    new UserMessage(prompt.userPrompt()))));
            String answer = response == null
                    || response.getResult() == null
                    || response.getResult().getOutput() == null
                    ? null
                    : response.getResult().getOutput().getText();
            Usage usage = response == null || response.getMetadata() == null
                    ? null
                    : response.getMetadata().getUsage();
            return new GenerationResult(
                    answer,
                    usage == null ? null : usage.getPromptTokens(),
                    usage == null ? null : usage.getCompletionTokens());
        } catch (RuntimeException exception) {
            throw new GenerationProviderException(exception);
        }
    }
}
