package com.portfolio.ragobservability.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import com.portfolio.ragobservability.document.embedding.EmbeddingGateway;
import com.portfolio.ragobservability.document.embedding.EmbeddingProperties;
import com.portfolio.ragobservability.document.embedding.EmbeddingProviderException;
import com.portfolio.ragobservability.document.embedding.EmbeddingVectorValidator;

class RetrievalServiceTests {

    private EmbeddingGateway gateway;
    private RetrievalRepository repository;
    private RetrievalProperties properties;

    @BeforeEach
    void setUp() {
        gateway = mock(EmbeddingGateway.class);
        repository = mock(RetrievalRepository.class);
        properties = new RetrievalProperties();
        when(gateway.isEnabled()).thenReturn(true);
        when(gateway.provider()).thenReturn("fake");
        when(gateway.model()).thenReturn("fake-1536");
        when(gateway.dimensions()).thenReturn(EmbeddingProperties.DATABASE_DIMENSIONS);
    }

    @Test
    void trimsQueryPreservesInternalWhitespaceAndUsesDefaults() {
        doReturn(List.of(validVector())).when(gateway).embed(any());
        when(repository.searchSimilar(any(), anyString(), anyString(), anyInt(), anyInt(), anyDouble()))
                .thenReturn(List.of(new SearchHit(1, 2, "title", 0, "content", 0.9)));

        RetrievalResult result = service().search("  database  timeout\ncheck  ", null, null);

        assertThat(result.query()).isEqualTo("database  timeout\ncheck");
        assertThat(result.topK()).isEqualTo(5);
        assertThat(result.minimumSimilarity()).isZero();
        assertThat(result.results()).hasSize(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> inputs = ArgumentCaptor.forClass(List.class);
        verify(gateway).embed(inputs.capture());
        assertThat(inputs.getValue()).containsExactly("database  timeout\ncheck");
        verify(repository).searchSimilar(any(),
                org.mockito.ArgumentMatchers.eq("fake"),
                org.mockito.ArgumentMatchers.eq("fake-1536"),
                org.mockito.ArgumentMatchers.eq(1536),
                org.mockito.ArgumentMatchers.eq(5),
                org.mockito.ArgumentMatchers.eq(0.0));
    }

    @Test
    void appliesRequestOverrides() {
        when(gateway.embed(any())).thenReturn(List.of(validVector()));
        when(repository.searchSimilar(any(), anyString(), anyString(), anyInt(), anyInt(), anyDouble()))
                .thenReturn(List.of());

        RetrievalResult result = service().search("query", 3, 0.75);

        assertThat(result.topK()).isEqualTo(3);
        assertThat(result.minimumSimilarity()).isEqualTo(0.75);
    }

    @Test
    void rejectsDisabledProviderBeforeCallingGatewayOrRepository() {
        when(gateway.isEnabled()).thenReturn(false);

        assertCode(() -> service().search("query", null, null), 503, "EMBEDDING_DISABLED");

        verify(gateway, never()).embed(any());
        verify(repository, never()).searchSimilar(
                any(), anyString(), anyString(), anyInt(), anyInt(), anyDouble());
    }

    @Test
    void rejectsInvalidResultCountDimensionsNumbersAndZeroVector() {
        assertInvalidEmbedding(List.of());
        assertInvalidEmbedding(List.of(validVector(), validVector()));
        assertInvalidEmbedding(List.of(new float[10]));

        float[] nan = validVector();
        nan[2] = Float.NaN;
        assertInvalidEmbedding(List.of(nan));

        float[] infinite = validVector();
        infinite[2] = Float.POSITIVE_INFINITY;
        assertInvalidEmbedding(List.of(infinite));

        assertInvalidEmbedding(List.of(new float[EmbeddingProperties.DATABASE_DIMENSIONS]));
    }

    @Test
    void mapsProviderAndDatabaseFailuresWithoutLeakingQuery() {
        when(gateway.embed(any())).thenThrow(new EmbeddingProviderException(
                new IllegalStateException("provider secret")));
        assertThatThrownBy(() -> service().search("sensitive query", null, null))
                .isInstanceOfSatisfying(RetrievalException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(502);
                    assertThat(exception.code()).isEqualTo("EMBEDDING_PROVIDER_FAILED");
                    assertThat(exception.getMessage()).doesNotContain("sensitive query", "provider secret");
                });

        doReturn(List.of(validVector())).when(gateway).embed(any());
        when(repository.searchSimilar(any(), anyString(), anyString(), anyInt(), anyInt(), anyDouble()))
                .thenThrow(new DataAccessResourceFailureException("SQL and vector details"));
        assertThatThrownBy(() -> service().search("sensitive query", null, null))
                .isInstanceOfSatisfying(RetrievalException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(500);
                    assertThat(exception.code()).isEqualTo("RETRIEVAL_FAILED");
                    assertThat(exception.getMessage()).doesNotContain(
                            "sensitive query", "SQL", "vector details");
                });
    }

    @Test
    void rejectsDirectCallInputOutsidePublicContract() {
        assertCode(() -> service().search("   ", null, null), 400, "VALIDATION_FAILED");
        assertCode(() -> service().search("x".repeat(2_001), null, null), 400, "VALIDATION_FAILED");
        assertCode(() -> service().search("query", 0, null), 400, "VALIDATION_FAILED");
        assertCode(() -> service().search("query", 21, null), 400, "VALIDATION_FAILED");
        assertCode(() -> service().search("query", null, Double.NaN), 400, "VALIDATION_FAILED");
        assertCode(() -> service().search("query", null, Double.POSITIVE_INFINITY),
                400, "VALIDATION_FAILED");
    }

    private void assertInvalidEmbedding(List<float[]> vectors) {
        when(gateway.embed(any())).thenReturn(vectors);
        assertCode(() -> service().search("query", null, null),
                502, "EMBEDDING_INVALID_RESPONSE");
        verify(repository, never()).searchSimilar(
                any(), anyString(), anyString(), anyInt(), anyInt(), anyDouble());
    }

    private RetrievalService service() {
        return new RetrievalService(
                gateway,
                new EmbeddingVectorValidator(),
                repository,
                properties);
    }

    private static void assertCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
            int status,
            String code) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(RetrievalException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(status);
                    assertThat(exception.code()).isEqualTo(code);
                });
    }

    private static float[] validVector() {
        float[] vector = new float[EmbeddingProperties.DATABASE_DIMENSIONS];
        vector[0] = 1.0f;
        return vector;
    }
}
