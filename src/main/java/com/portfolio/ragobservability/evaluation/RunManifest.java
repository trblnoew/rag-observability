package com.portfolio.ragobservability.evaluation;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Versioned run snapshot. Reading this file never depends on today's dataset or database. */
public record RunManifest(int formatVersion, boolean complete, GoldenDataset dataset, Map<String, Long> categoryCounts,
                          int topK, double minimumSimilarity, String provider, String model, int dimensions,
                          String startedAt, String gitCommit, String gitDirty, String chunkSizeProvenance,
                          List<EvaluationCorpus.Document> documents, Map<String, String> fileHashes) {
    public static void write(Path output, EvaluationRunner.Run run, String summary) throws IOException {
        Map<String,String> hashes = new TreeMap<>();
        for (String file : List.of("queries.csv", "hits.csv")) hashes.put(file, GoldenDataset.hash(Files.readAllBytes(output.resolve(file))));
        hashes.put("summary.md", GoldenDataset.hash(summary.getBytes(StandardCharsets.UTF_8)));
        var manifest = new RunManifest(2, run.complete(), run.dataset(), run.dataset().categoryCounts(), 5, -1,
                run.provider(), run.model(), run.dimensions(), run.startedAt().toString(), run.gitCommit(), run.gitDirty(),
                "600 tokens (operator assumption; not persisted in schema)", run.documents(), hashes);
        Path pending = output.resolve("run.pending");
        Files.writeString(pending, GoldenDataset.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(manifest), StandardOpenOption.CREATE_NEW);
        Files.move(pending, output.resolve("run.json"));
    }
    public static RunManifest read(Path file, Map<String,String> actualHashes) throws IOException {
        try {
            var m = GoldenDataset.mapper().readValue(Files.readAllBytes(file), RunManifest.class);
            if (m == null || m.formatVersion()!=2 || !m.complete() || m.dataset().formatVersion()!=2
                    || m.topK()!=5 || m.minimumSimilarity()!=-1 || m.dimensions()!=1536
                    || m.provider().isBlank() || m.model().isBlank() || m.gitCommit().isBlank() || m.gitDirty().isBlank()
                    || !m.chunkSizeProvenance().equals("600 tokens (operator assumption; not persisted in schema)")) throw new IOException();
            java.time.Instant.parse(m.startedAt());
            m.dataset().validate();
            if (!m.categoryCounts().equals(m.dataset().categoryCounts()) || !m.fileHashes().equals(actualHashes)) throw new IOException();
            if (m.documents().size()!=3 || m.documents().stream().map(EvaluationCorpus.Document::id).distinct().count()!=3
                    || m.documents().stream().map(EvaluationCorpus.Document::filename).distinct().count()!=3) throw new IOException();
            for (var d : m.documents()) if (d.id()<=0 || d.chunkCount()<=0 || !d.sha256().equals(m.dataset().documents().get(d.filename()))) throw new IOException();
            return m;
        } catch (IOException | RuntimeException e) { throw new IOException("INVALID_RUN_MANIFEST"); }
    }
}
