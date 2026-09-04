package com.portfolio.ragobservability.rag;

import java.net.http.HttpClient;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({GenerationProperties.class, AnswerProperties.class})
public class GenerationConfiguration {

    @Bean
    @ConditionalOnProperty(
            name = "rag.generation.provider",
            havingValue = "none",
            matchIfMissing = true)
    GenerationGateway disabledGenerationGateway(GenerationProperties properties) {
        return new DisabledGenerationGateway(properties.getModel());
    }

    @Bean
    @ConditionalOnProperty(name = "rag.generation.provider", havingValue = "openai")
    GenerationGateway openAiGenerationGateway(GenerationProperties properties) {
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
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(properties.getModel())
                .temperature(properties.getTemperature())
                .maxTokens(properties.getMaxOutputTokens())
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).noBackoff().build())
                .build();
        return new OpenAiGenerationGateway(chatModel, properties.getModel());
    }
}
