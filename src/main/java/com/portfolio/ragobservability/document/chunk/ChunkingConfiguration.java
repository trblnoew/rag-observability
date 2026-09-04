package com.portfolio.ragobservability.document.chunk;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChunkingProperties.class)
public class ChunkingConfiguration {

    @Bean
    TokenTextSplitter tokenTextSplitter(ChunkingProperties properties) {
        return TokenTextSplitter.builder()
                .withChunkSize(properties.chunkSizeTokens())
                .build();
    }

    @Bean
    TokenCountEstimator tokenCountEstimator() {
        return new JTokkitTokenCountEstimator();
    }
}
