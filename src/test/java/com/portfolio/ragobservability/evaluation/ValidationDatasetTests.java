package com.portfolio.ragobservability.evaluation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.portfolio.ragobservability.document.embedding.EmbeddingGateway;
import com.portfolio.ragobservability.retrieval.*;
import com.portfolio.ragobservability.evaluation.threshold.*;

class ValidationDatasetTests {
    @TempDir Path temp;
    final Path input = Path.of("evaluation/retrieval-validation-set-v1.json");

    @Test void validatesFrozenSourcesAndOfflineEntryPoint() throws Exception {
        var data = GoldenDataset.load(input);
        assertThat(data.queries()).hasSize(18);
        assertThat(data.categoryCounts()).containsEntry("DIRECT", 6L).containsEntry("PARAPHRASED", 6L)
                .containsEntry("HARD_NEGATIVE", 3L).containsEntry("UNRELATED", 3L);
        data.validateLocalSources(Path.of("samples/retrieval-lab"));
        assertThat(DatasetValidationMain.run(new String[]{input.toString()})).isZero();
        assertThat(DatasetValidationMain.run(new String[]{"missing.json"})).isEqualTo(1);
        for (String name : data.documents().keySet()) Files.writeString(temp.resolve(name), "changed");
        assertThatThrownBy(() -> data.validateLocalSources(temp)).hasMessageContaining("SOURCE_HASH_MISMATCH");
    }

    @Test void rejectsInvalidNewDatasetAndLegacyOverlap() throws Exception {
        for (String mutation : List.of("reason", "duplicate", "overlap", "negative", "hash", "category", "count")) {
            var root = GoldenDataset.mapper().readTree(Files.readString(input));
            var row = (ObjectNode)root.get("queries").get(0);
            switch (mutation) {
                case "reason" -> row.remove("judgmentReason");
                case "duplicate" -> row.put("question", "  " + root.get("queries").get(1).get("question").asText() + "  ");
                case "overlap" -> row.put("question", GoldenDataset.load(Path.of("evaluation/retrieval-golden-set.json")).queries().getFirst().question());
                case "negative" -> ((ObjectNode)root.get("queries").get(12)).put("relevantDocument", "cpu-spike.md");
                case "hash" -> ((ObjectNode)root.get("documents")).put("cpu-spike.md", "invalid");
                case "category" -> row.put("category", "HARD_NEGATIVE");
                case "count" -> ((com.fasterxml.jackson.databind.node.ArrayNode)root.get("queries")).remove(17);
            }
            Path file = temp.resolve(mutation + ".json"); Files.writeString(file, root.toString());
            assertThatThrownBy(() -> GoldenDataset.load(file)).isInstanceOf(java.io.IOException.class);
        }
    }

    @Test void thresholdsAreFiniteSortedDeduplicatedWithControl() {
        assertThat(ThresholdCalculator.parseThresholds("0.5,0.3,0.4,0.3")).containsExactly(-1.0, .3, .4, .5);
        for (String value : List.of("NaN", "Infinity", "-Infinity", "1.1", "-1.1", "", "0.3,"))
            assertThatThrownBy(() -> ThresholdCalculator.parseThresholds(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void failedNewRunStopsAndCannotBeCompared() throws Exception {
        var data = GoldenDataset.load(input);
        var gateway = mock(EmbeddingGateway.class);
        when(gateway.provider()).thenReturn("fake"); when(gateway.model()).thenReturn("fake-1536"); when(gateway.dimensions()).thenReturn(1536);
        var corpus = mock(EvaluationCorpus.class);
        var names = data.documents().keySet().stream().sorted().toList();
        var docs = java.util.stream.IntStream.range(0, names.size()).mapToObj(i ->
                new EvaluationCorpus.Document(i+1, names.get(i), data.documents().get(names.get(i)), 1)).toList();
        when(corpus.validate(gateway)).thenReturn(docs);
        var retrieval = mock(RetrievalService.class);
        when(retrieval.search(anyString(), eq(5), eq(-1.0)))
                .thenReturn(new RetrievalResult("query", List.of(), 5, -1, "fake", "fake-1536", 1536))
                .thenThrow(new RetrievalException(org.springframework.http.HttpStatus.BAD_GATEWAY, "EMBEDDING_PROVIDER_FAILED", "SECRET"));
        assertThat(new EvaluationRunner(retrieval, gateway, corpus, new EvaluationReport()).run(input, temp, "abc", "false")).isEqualTo(1);
        verify(retrieval, times(2)).search(anyString(), eq(5), eq(-1.0));
        Path dir; try (var paths = Files.list(temp)) { dir = paths.findFirst().orElseThrow(); }
        assertThat(Files.readString(dir.resolve("summary.md"))).contains("INCOMPLETE", "V-DB-02").doesNotContain("SECRET", "Hit@5: ");
        assertThatThrownBy(() -> ThresholdBaseline.load(dir)).isInstanceOf(java.io.IOException.class);
    }
}
