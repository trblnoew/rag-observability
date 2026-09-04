package com.portfolio.ragobservability.rag;

public class DisabledGenerationGateway implements GenerationGateway {

    private final String model;

    public DisabledGenerationGateway(String model) {
        this.model = model;
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public String provider() {
        return "none";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public GenerationResult generate(GenerationPrompt prompt) {
        throw new GenerationProviderException(
                new IllegalStateException("Generation provider is disabled"));
    }
}
