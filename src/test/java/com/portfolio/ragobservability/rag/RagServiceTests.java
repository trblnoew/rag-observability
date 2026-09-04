package com.portfolio.ragobservability.rag;

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

import com.portfolio.ragobservability.retrieval.RetrievalResult;
import com.portfolio.ragobservability.retrieval.RetrievalService;
import com.portfolio.ragobservability.retrieval.SearchHit;

class RagServiceTests {

    private RetrievalService retrievalService;
    private GenerationGateway generationGateway;
    private AnswerProperties properties;

    @BeforeEach
    void setUp() {
        retrievalService = mock(RetrievalService.class);
        generationGateway = mock(GenerationGateway.class);
        properties = new AnswerProperties();
        when(generationGateway.isEnabled()).thenReturn(true);
        when(generationGateway.provider()).thenReturn("fake-generation");
        when(generationGateway.model()).thenReturn("fake-chat");
    }

    @Test
    void retrievesBuildsContextValidatesAnswerAndMapsUsage() {
        when(retrievalService.search("database timeout", 3, 0.7))
                .thenReturn(retrieval(List.of(
                        new SearchHit(1, 2, "DB", 0, "pool content", 0.9)), 3, 0.7));
        when(generationGateway.generate(any()))
                .thenReturn(new GenerationResult("check pool [S1]", 100, 20));

        RagResult result = service().answer("  database timeout  ", 3, 0.7);

        assertThat(result.question()).isEqualTo("database timeout");
        assertThat(result.answer()).isEqualTo("check pool [S1]");
        assertThat(result.sources()).hasSize(1);
        assertThat(result.generationCalled()).isTrue();
        assertThat(result.inputTokens()).isEqualTo(100);
        assertThat(result.outputTokens()).isEqualTo(20);
        verify(generationGateway).generate(any());
    }

    @Test
    void usesAnswerThresholdAndSkipsGenerationWhenNoEvidence() {
        when(retrievalService.search("unknown", null, 0.5))
                .thenReturn(retrieval(List.of(), 5, 0.5));

        RagResult result = service().answer("unknown", null, null);

        assertThat(result.answer()).isEqualTo(RagService.NO_EVIDENCE_ANSWER);
        assertThat(result.sources()).isEmpty();
        assertThat(result.generationCalled()).isFalse();
        assertThat(result.inputTokens()).isNull();
        verify(generationGateway, never()).generate(any());
    }

    @Test
    void rejectsDisabledGenerationBeforeRetrieval() {
        when(generationGateway.isEnabled()).thenReturn(false);

        assertCode(() -> service().answer("question", null, null), 503, "GENERATION_DISABLED");

        verify(retrievalService, never()).search(anyString(), any(), anyDouble());
    }

    @Test
    void mapsProviderAndInvalidResponseWithoutLeakingDetails() {
        when(retrievalService.search(anyString(), any(), anyDouble()))
                .thenReturn(retrieval(List.of(
                        new SearchHit(1, 2, "DB", 0, "sensitive context", 0.9)), 5, 0.5));
        when(generationGateway.generate(any())).thenThrow(
                new GenerationProviderException(new IllegalStateException("provider secret")));

        assertThatThrownBy(() -> service().answer("sensitive question", null, null))
                .isInstanceOfSatisfying(RagException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("GENERATION_PROVIDER_FAILED");
                    assertThat(exception.getMessage()).doesNotContain(
                            "sensitive question", "sensitive context", "provider secret");
                });

        doReturn(new GenerationResult("invented [S2]", null, null))
                .when(generationGateway).generate(any());
        assertCode(() -> service().answer("question", null, null),
                502, "GENERATION_INVALID_RESPONSE");
    }

    @Test
    void rejectsDirectCallInputOutsidePublicContract() {
        assertCode(() -> service().answer(" ", null, null), 400, "VALIDATION_FAILED");
        assertCode(() -> service().answer("x".repeat(2_001), null, null),
                400, "VALIDATION_FAILED");
        assertCode(() -> service().answer("question", 0, null), 400, "VALIDATION_FAILED");
        assertCode(() -> service().answer("question", 21, null), 400, "VALIDATION_FAILED");
        assertCode(() -> service().answer("question", null, Double.NaN),
                400, "VALIDATION_FAILED");
    }

    private RagService service() {
        return new RagService(
                retrievalService,
                generationGateway,
                new ContextBuilder(properties),
                new RagPromptBuilder(),
                new CitationValidator(),
                properties);
    }

    private static RetrievalResult retrieval(List<SearchHit> hits, int topK, double similarity) {
        return new RetrievalResult(
                hits.isEmpty() ? "unknown" : "database timeout",
                hits,
                topK,
                similarity,
                "fake-embedding",
                "fake-1536",
                1536);
    }

    private static void assertCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
            int status,
            String code) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(RagException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(status);
                    assertThat(exception.code()).isEqualTo(code);
                });
    }
}
