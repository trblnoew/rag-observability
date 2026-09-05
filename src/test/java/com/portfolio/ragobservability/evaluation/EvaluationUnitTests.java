package com.portfolio.ragobservability.evaluation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import com.portfolio.ragobservability.document.embedding.EmbeddingGateway;
import com.portfolio.ragobservability.retrieval.*;

class EvaluationUnitTests {
    @TempDir Path temp;
    private final Path dataset = Path.of("evaluation/retrieval-golden-set.json");
    private static SearchHit hit(long doc) { return new SearchHit(doc, doc, "title", 0, "SECRET_CHUNK", .8); }

    @Test void datasetIsBalancedAndHasStableHash() throws Exception {
        var data = GoldenDataset.load(dataset);
        assertThat(data.queries()).hasSize(15);
        assertThat(data.sha256()).hasSize(64).isEqualTo(GoldenDataset.load(dataset).sha256());
        assertThat(data.queries().stream().filter(q -> q.category() == GoldenDataset.Category.UNRELATED)).hasSize(3);
    }

    @Test void rejectsMalformedAndInvalidDatasetBeforeRequests() throws Exception {
        var mapper = new ObjectMapper();
        for (String kind : List.of("duplicate", "blank", "long", "category", "missing", "unrelated", "unknown", "balance", "null")) {
            var root = mapper.readTree(Files.readString(dataset));
            var row = (com.fasterxml.jackson.databind.node.ObjectNode) root.get(0);
            switch (kind) {
                case "duplicate" -> row.put("queryId", root.get(1).get("queryId").asText());
                case "blank" -> row.put("question", "   ");
                case "long" -> row.put("question", "x".repeat(2001));
                case "category" -> row.put("category", "INVALID");
                case "missing" -> row.remove("relevantDocument");
                case "unrelated" -> row.put("category", "UNRELATED");
                case "unknown" -> row.put("unexpected", true);
                case "balance" -> row.put("category", "PARAPHRASED");
                case "null" -> ((com.fasterxml.jackson.databind.node.ArrayNode) root).set(0, mapper.nullNode());
            }
            Path invalid = temp.resolve(kind + ".json");
            Files.writeString(invalid, root.toString());
            assertThatThrownBy(() -> GoldenDataset.load(invalid)).isInstanceOf(java.io.IOException.class);
        }
        Path invalid = temp.resolve("broken.json");
        Files.writeString(invalid, "[SECRET_QUESTION");
        assertThatThrownBy(() -> GoldenDataset.load(invalid)).hasMessage("INVALID_DATASET");
    }

    @Test void scoresChunkRanksWithoutDeduplicatingDocuments() {
        var first = EvaluationMetrics.score(1L, List.of(hit(1), hit(2)));
        var third = EvaluationMetrics.score(2L, List.of(hit(1), hit(1), hit(2)));
        var absent = EvaluationMetrics.score(3L, List.of(hit(1)));
        var unrelated = EvaluationMetrics.score(null, List.of(hit(1)));
        assertThat(first.firstRelevantRank()).isEqualTo(1);
        assertThat(third.firstRelevantRank()).isEqualTo(3);
        assertThat(third.reciprocalRank()).isEqualTo(1.0 / 3);
        assertThat(absent.hit()).isFalse();
        assertThat(absent.reciprocalRank()).isZero();
        assertThat(unrelated.hit()).isNull();
        assertThat(unrelated.reciprocalRank()).isNull();
        assertThat(EvaluationMetrics.hitRate(List.of(first, third, absent, unrelated))).isEqualTo(2.0 / 3);
        assertThat(EvaluationMetrics.mrr(List.of(first, third, absent, unrelated))).isEqualTo((1.0 + 1.0 / 3) / 3);
    }

    @Test void percentilesUseNearestRank() {
        var times = java.util.stream.IntStream.rangeClosed(1, 15).mapToObj(i -> (double) i).toList();
        assertThat(EvaluationMetrics.percentile(times, .5)).isEqualTo(8.0);
        assertThat(EvaluationMetrics.percentile(times, .95)).isEqualTo(15.0);
        assertThat(EvaluationMetrics.percentile(List.of(), .5)).isNull();
    }

    @Test void csvEscapesQuotesCommasAndNewlinesAndDirectoriesAreUnique() throws Exception {
        assertThat(EvaluationReport.csv("a,b", "a\"b", "a\nb", null)).isEqualTo("\"a,b\",\"a\"\"b\",\"a\nb\",\r\n");
        var report = new EvaluationReport();
        Path first = report.prepare(temp);
        Files.writeString(first.resolve("summary.md"), "existing");
        assertThat(report.prepare(temp)).isNotEqualTo(first);
        assertThat(Files.readString(first.resolve("summary.md"))).isEqualTo("existing");
    }

    @Test void failureStopsCallsAndDoesNotPublishFinalMetricsOrSecrets() throws Exception {
        var gateway = mock(EmbeddingGateway.class);
        when(gateway.provider()).thenReturn("fake"); when(gateway.model()).thenReturn("fake-1536"); when(gateway.dimensions()).thenReturn(1536);
        var corpus = mock(EvaluationCorpus.class);
        when(corpus.validate(gateway)).thenReturn(List.of(new EvaluationCorpus.Document(1, "db-connection-timeout.md", "a".repeat(64), 1)));
        var retrieval = mock(RetrievalService.class);
        when(retrieval.search(anyString(), eq(5), eq(-1.0)))
                .thenReturn(new RetrievalResult("query", List.of(hit(1)), 5, -1, "fake", "fake-1536", 1536))
                .thenThrow(new RetrievalException(HttpStatus.BAD_GATEWAY, "EMBEDDING_PROVIDER_FAILED", "SECRET_PROVIDER_ERROR"));
        int code = new EvaluationRunner(retrieval, gateway, corpus, new EvaluationReport()).run(dataset, temp, "abc", "true");
        assertThat(code).isEqualTo(1);
        verify(retrieval, times(2)).search(anyString(), eq(5), eq(-1.0));
        Path dir; try (var paths = Files.list(temp)) { dir = paths.findFirst().orElseThrow(); }
        assertThat(Files.readString(dir.resolve("summary.md"))).contains("INCOMPLETE", "not published", "DB-002", "EMBEDDING_PROVIDER_FAILED")
                .doesNotContain("SECRET_PROVIDER_ERROR", "SECRET_CHUNK", "Hit@5: ");
        assertThat(Files.readString(dir.resolve("queries.csv"))).contains("EMBEDDING_PROVIDER_FAILED").doesNotContain("SECRET_PROVIDER_ERROR");
        assertThat(Files.readString(dir.resolve("hits.csv"))).doesNotContain("SECRET_CHUNK");
    }

    @Test void unwritableOutputPreventsAllSearchCalls() throws Exception {
        Path file = temp.resolve("file"); Files.writeString(file, "not a directory");
        var retrieval = mock(RetrievalService.class);
        int code = new EvaluationRunner(retrieval, mock(EmbeddingGateway.class), mock(EvaluationCorpus.class), new EvaluationReport())
                .run(dataset, file, "unavailable", "unknown");
        assertThat(code).isEqualTo(1); verifyNoInteractions(retrieval);
    }

    @Test void disabledProviderAndWrongDimensionsAreRejectedBeforeDatabaseAccess() {
        var jdbc = mock(org.springframework.jdbc.core.simple.JdbcClient.class);
        var gateway = mock(EmbeddingGateway.class);
        var corpus = new EvaluationCorpus(jdbc);
        assertThatThrownBy(() -> corpus.validate(gateway)).hasMessage("EMBEDDING_DISABLED");
        when(gateway.isEnabled()).thenReturn(true);
        when(gateway.dimensions()).thenReturn(768);
        assertThatThrownBy(() -> corpus.validate(gateway)).hasMessage("INVALID_EMBEDDING_DIMENSIONS");
        verifyNoInteractions(jdbc);
    }

    @Test void preflightFailureWritesSafeActionableCodeAndMakesNoSearchCalls() throws Exception {
        var retrieval = mock(RetrievalService.class);
        var gateway = mock(EmbeddingGateway.class);
        var corpus = mock(EvaluationCorpus.class);
        when(corpus.validate(gateway)).thenThrow(new IllegalStateException("EXTRA_ELIGIBLE_DOCUMENTS"));
        assertThat(new EvaluationRunner(retrieval, gateway, corpus, new EvaluationReport())
                .run(dataset, temp, "unavailable", "unknown")).isEqualTo(1);
        verifyNoInteractions(retrieval);
        Path dir; try (var paths = Files.list(temp)) { dir = paths.findFirst().orElseThrow(); }
        assertThat(Files.readString(dir.resolve("summary.md"))).contains("INCOMPLETE", "EXTRA_ELIGIBLE_DOCUMENTS");
    }
}
