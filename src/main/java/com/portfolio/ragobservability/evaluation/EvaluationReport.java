package com.portfolio.ragobservability.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public final class EvaluationReport {
    public Path prepare(Path root) throws IOException {
        Files.createDirectories(root);
        Path run = Files.createDirectory(root.resolve(Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID()));
        // Reserve every output before any paid request; CREATE_NEW also prevents overwriting previous runs.
        for (String name : List.of("queries.csv", "hits.csv", "summary.md")) Files.createFile(run.resolve(name));
        return run;
    }
    public void failure(Path output, String safeCode) throws IOException {
        writeText(output.resolve("summary.md"), "# Retrieval baseline\n\nINCOMPLETE\n\n" + safeCode + "\n");
    }
    public void write(Path output, EvaluationRunner.Run run) throws IOException {
        StringBuilder queries = new StringBuilder(csv("queryId", "category", "expectedDocument", "resultCount", "firstRelevantRank", "hit", "reciprocalRank", "topSimilarity", "latencyMs", "errorCode"));
        StringBuilder hits = new StringBuilder(csv("queryId", "rank", "documentId", "originalFilename", "chunkId", "chunkIndex", "similarity"));
        Map<Long, String> filenames = run.documents().stream().collect(Collectors.toMap(EvaluationCorpus.Document::id, EvaluationCorpus.Document::filename));
        for (EvaluationRunner.Row row : run.rows()) {
            Double top = row.hits().isEmpty() ? null : row.hits().getFirst().similarity();
            queries.append(csv(row.query().queryId(), row.query().category(), row.query().relevantDocument(),
                    row.errorCode() == null ? row.hits().size() : null, row.score().firstRelevantRank(), row.score().hit(),
                    row.score().reciprocalRank(), top, row.latencyMs(), row.errorCode()));
            for (int i = 0; i < row.hits().size(); i++) {
                var hit = row.hits().get(i);
                hits.append(csv(row.query().queryId(), i + 1, hit.documentId(), filenames.get(hit.documentId()), hit.chunkId(), hit.chunkIndex(), hit.similarity()));
            }
        }
        StringBuilder md = new StringBuilder("# Retrieval baseline\n\n");
        md.append(run.complete() ? "COMPLETE" : "INCOMPLETE").append("\n\n");
        md.append("- Started (UTC): ").append(run.startedAt()).append('\n');
        md.append("- Git commit: ").append(markdown(run.gitCommit())).append("; dirty: ").append(markdown(run.gitDirty())).append('\n');
        md.append("- Dataset SHA-256: ").append(run.datasetSha256()).append('\n');
        md.append("- Provider/model/dimensions: ").append(markdown(run.provider())).append(" / ").append(markdown(run.model())).append(" / ").append(run.dimensions()).append('\n');
        md.append("- Top-K: 5 chunks; minimum similarity: -1.0; cosine exact search\n");
        md.append("- Corpus chunk-size provenance: 600 tokens (operator assumption; not persisted in schema)\n");
        md.append("- Logical queries attempted: ").append(run.rows().size()).append(" / ").append(run.dataset().queries().size()).append('\n');
        md.append("- Latency: Query Embedding + DB search; excludes HTTP, LLM and report writing. No warmup.\n");
        md.append("- Percentiles: nearest-rank on successful requests; small exploratory sample, not a load test.\n\n");
        List<EvaluationRunner.Row> success = run.rows().stream().filter(r -> r.errorCode() == null).toList();
        List<Double> timings = success.stream().map(EvaluationRunner.Row::latencyMs).toList();
        if (run.complete()) {
            var scores = success.stream().map(EvaluationRunner.Row::score).toList();
            md.append("Hit@5: ").append(format(EvaluationMetrics.hitRate(scores))).append("\n\nMRR@5: ")
                    .append(format(EvaluationMetrics.mrr(scores))).append("\n\n");
        } else md.append("Hit@5 / MRR@5: not published for incomplete runs.\n\n");
        md.append("p50 ms: ").append(format(EvaluationMetrics.percentile(timings, .5)))
                .append("; p95 ms: ").append(format(EvaluationMetrics.percentile(timings, .95))).append("\n\n");
        md.append("| Document ID | Filename | Raw content SHA-256 | Chunk count |\n|---|---|---|---|\n");
        for (var d : run.documents()) md.append("| ").append(d.id()).append(" | ").append(markdown(d.filename())).append(" | ").append(d.sha256()).append(" | ").append(d.chunkCount()).append(" |\n");
        md.append("\n| Query ID | Category | First relevant rank | Hit | RR | Top similarity | Latency ms | Review |\n|---|---|---|---|---|---|---|---|\n");
        for (var row : run.rows()) {
            String review = row.errorCode() != null ? row.errorCode() : row.score().hit() == null ? row.query().category() + ": observe scores"
                    : !row.score().hit() ? "MISS" : row.score().firstRelevantRank() > 1 ? "REVIEW_RANK" : "OK";
            md.append("| ").append(row.query().queryId()).append(" | ").append(row.query().category()).append(" | ")
                    .append(row.score().firstRelevantRank() == null ? "" : row.score().firstRelevantRank()).append(" | ")
                    .append(row.score().hit() == null ? "" : row.score().hit()).append(" | ").append(format(row.score().reciprocalRank())).append(" | ")
                    .append(format(row.hits().isEmpty() ? null : row.hits().getFirst().similarity())).append(" | ")
                    .append(format(row.latencyMs())).append(" | ").append(review).append(" |\n");
        }
        md.append("\nDocument-level relevance only: a hit does not prove the returned chunk answers the question.\nUnrelated queries are excluded from Hit/MRR.\n");
        writeText(output.resolve("queries.csv"), queries.toString());
        writeText(output.resolve("hits.csv"), hits.toString());
        // Keep the reserved summary non-COMPLETE until all output artifacts are ready.
        Path pending = output.resolve("summary.pending");
        Files.writeString(pending, md.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        if (run.dataset().formatVersion() == 2) {
            RunManifest.write(output, run, md.toString());
        }
        Files.move(pending, output.resolve("summary.md"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    static String csv(Object... values) {
        return Arrays.stream(values).map(v -> v == null ? "" : "\"" + v.toString().replace("\"", "\"\"") + "\"")
                .collect(Collectors.joining(",")) + "\r\n";
    }
    private static String markdown(String value) { return value.replace("|", "\\|").replace("\n", " ").replace("\r", " "); }
    private static String format(Double value) { return value == null ? "" : String.format(Locale.ROOT, "%.4f", value); }
    private static void writeText(Path path, String text) throws IOException {
        Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
