package com.portfolio.ragobservability.document.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.portfolio.ragobservability.document.DocumentException;
class EmbeddingServiceTests {

    private EmbeddingProperties properties;
    private EmbeddingTransactions transactions;
    private DocumentEmbeddingRepository embeddingRepository;

    @BeforeEach
    void setUp() {
        properties = new EmbeddingProperties();
        properties.setBatchSize(32);
        transactions = mock(EmbeddingTransactions.class);
        embeddingRepository = mock(DocumentEmbeddingRepository.class);
        when(embeddingRepository.existsDocument(1L)).thenReturn(true);
        when(transactions.claim(1L)).thenReturn(new EmbeddingClaim(1L, 0));
    }

    @Test
    void batchesSeventyChunksAsThirtyTwoThirtyTwoSixAndPreservesOrder() {
        RecordingGateway gateway = new RecordingGateway();
        List<EmbeddableChunk> chunks = java.util.stream.IntStream.range(0, 70)
                .mapToObj(index -> new EmbeddableChunk(index + 1L, index, "chunk-" + index))
                .toList();
        when(embeddingRepository.findChunks(1L)).thenReturn(chunks);

        EmbeddingService service = service(gateway);
        var response = service.process(1L);

        assertThat(gateway.batchSizes).containsExactly(32, 32, 6);
        assertThat(response.embeddedChunkCount()).isEqualTo(70);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChunkEmbedding>> captor = ArgumentCaptor.forClass(List.class);
        verify(transactions).complete(
                org.mockito.ArgumentMatchers.eq(1L),
                captor.capture(),
                org.mockito.ArgumentMatchers.eq("fake"),
                org.mockito.ArgumentMatchers.eq("fake-1536"));
        List<ChunkEmbedding> saved = captor.getValue();
        assertThat(saved).hasSize(70);
        assertThat(saved.get(0).chunkId()).isEqualTo(1L);
        assertThat(saved.get(0).vector()[0]).isEqualTo(1.0f);
        assertThat(saved.get(69).chunkId()).isEqualTo(70L);
        assertThat(saved.get(69).vector()[0]).isEqualTo(70.0f);
    }

    @Test
    void rejectsWrongDimensionAndPersistsFailedState() {
        when(embeddingRepository.findChunks(1L))
                .thenReturn(List.of(new EmbeddableChunk(10L, 0, "chunk")));
        EmbeddingGateway gateway = gatewayReturning(List.of(new float[10]));

        assertThatThrownBy(() -> service(gateway).process(1L))
                .isInstanceOfSatisfying(DocumentException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(502);
                    assertThat(exception.code()).isEqualTo("EMBEDDING_INVALID_RESPONSE");
                });

        verify(transactions).fail(
                1L,
                "EMBEDDING_INVALID_RESPONSE",
                "Embedding vector dimensions do not match the configured dimensions");
        verify(transactions, never()).complete(
                anyLong(),
                org.mockito.ArgumentMatchers.anyList(),
                anyString(),
                anyString());
    }

    @Test
    void rejectsResultCountMismatch() {
        when(embeddingRepository.findChunks(1L)).thenReturn(List.of(
                new EmbeddableChunk(10L, 0, "first"),
                new EmbeddableChunk(11L, 1, "second")));
        EmbeddingGateway gateway = gatewayReturning(List.of(validVector(1.0f)));

        assertThatThrownBy(() -> service(gateway).process(1L))
                .isInstanceOfSatisfying(DocumentException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(502);
                    assertThat(exception.code()).isEqualTo("EMBEDDING_INVALID_RESPONSE");
                    assertThat(exception.getMessage()).contains("count");
                });

        verify(transactions, never()).complete(
                anyLong(),
                org.mockito.ArgumentMatchers.anyList(),
                anyString(),
                anyString());
    }

    @Test
    void rejectsNonFiniteAndAllZeroVectors() {
        when(embeddingRepository.findChunks(1L))
                .thenReturn(List.of(new EmbeddableChunk(10L, 0, "chunk")));
        float[] nonFinite = validVector(1.0f);
        nonFinite[5] = Float.NaN;

        assertThatThrownBy(() -> service(gatewayReturning(List.of(nonFinite))).process(1L))
                .isInstanceOfSatisfying(DocumentException.class,
                        exception -> assertThat(exception.code()).isEqualTo("EMBEDDING_INVALID_RESPONSE"));

        when(transactions.claim(1L)).thenReturn(new EmbeddingClaim(1L, 1));
        float[] infinite = validVector(1.0f);
        infinite[5] = Float.POSITIVE_INFINITY;
        assertThatThrownBy(() -> service(gatewayReturning(List.of(infinite))).process(1L))
                .isInstanceOfSatisfying(DocumentException.class,
                        exception -> assertThat(exception.code()).isEqualTo("EMBEDDING_INVALID_RESPONSE"));

        when(transactions.claim(1L)).thenReturn(new EmbeddingClaim(1L, 2));
        assertThatThrownBy(() -> service(gatewayReturning(List.of(
                        new float[EmbeddingProperties.DATABASE_DIMENSIONS]))).process(1L))
                .isInstanceOfSatisfying(DocumentException.class,
                        exception -> assertThat(exception.code()).isEqualTo("EMBEDDING_INVALID_RESPONSE"));
    }

    @Test
    void disabledGatewayDoesNotClaimDocument() {
        EmbeddingGateway gateway = new DisabledEmbeddingGateway(1536);

        assertThatThrownBy(() -> service(gateway).process(1L))
                .isInstanceOfSatisfying(DocumentException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(503);
                    assertThat(exception.code()).isEqualTo("EMBEDDING_DISABLED");
                });

        verify(transactions, never()).claim(anyLong());
    }

    private EmbeddingService service(EmbeddingGateway gateway) {
        return new EmbeddingService(
                gateway,
                properties,
                transactions,
                embeddingRepository,
                new EmbeddingVectorValidator());
    }

    private static EmbeddingGateway gatewayReturning(List<float[]> vectors) {
        return new EmbeddingGateway() {
            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public String provider() {
                return "fake";
            }

            @Override
            public String model() {
                return "fake-1536";
            }

            @Override
            public int dimensions() {
                return EmbeddingProperties.DATABASE_DIMENSIONS;
            }

            @Override
            public List<float[]> embed(List<String> inputs) {
                return vectors;
            }
        };
    }

    private static float[] validVector(float firstValue) {
        float[] vector = new float[EmbeddingProperties.DATABASE_DIMENSIONS];
        vector[0] = firstValue;
        return vector;
    }

    private static class RecordingGateway implements EmbeddingGateway {

        private final List<Integer> batchSizes = new ArrayList<>();

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String provider() {
            return "fake";
        }

        @Override
        public String model() {
            return "fake-1536";
        }

        @Override
        public int dimensions() {
            return EmbeddingProperties.DATABASE_DIMENSIONS;
        }

        @Override
        public List<float[]> embed(List<String> inputs) {
            batchSizes.add(inputs.size());
            return inputs.stream()
                    .map(input -> validVector(Integer.parseInt(input.substring("chunk-".length())) + 1.0f))
                    .toList();
        }
    }
}
