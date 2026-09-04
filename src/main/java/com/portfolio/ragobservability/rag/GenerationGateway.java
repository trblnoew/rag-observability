package com.portfolio.ragobservability.rag;

public interface GenerationGateway {

    boolean isEnabled();

    String provider();

    String model();

    GenerationResult generate(GenerationPrompt prompt);
}
