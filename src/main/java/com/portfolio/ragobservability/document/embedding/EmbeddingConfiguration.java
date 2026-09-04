package com.portfolio.ragobservability.document.embedding;

import java.net.http.HttpClient;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingConfiguration {

    @Bean
    @ConditionalOnProperty(
            name = "rag.embedding.provider",
            havingValue = "none",
            matchIfMissing = true)
    EmbeddingGateway disabledEmbeddingGateway(EmbeddingProperties properties) {
        return new DisabledEmbeddingGateway(properties.getDimensions());
    }

    @Bean
    @ConditionalOnProperty(name = "rag.embedding.provider", havingValue = "openai")
    EmbeddingGateway openAiEmbeddingGateway(EmbeddingProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(requestFactory);
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .restClientBuilder(restClientBuilder)
                .build();
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(properties.getModel())
                .dimensions(properties.getDimensions())
                .build();
        OpenAiEmbeddingModel embeddingModel = new OpenAiEmbeddingModel(
                openAiApi,
                MetadataMode.NONE,
                options,
                RetryTemplate.builder().maxAttempts(1).noBackoff().build());
        return new OpenAiEmbeddingGateway(
                embeddingModel,
                properties.getModel(),
                properties.getDimensions());
    }
}
