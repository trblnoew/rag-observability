package com.portfolio.ragobservability.evaluation;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.portfolio.ragobservability.document.embedding.EmbeddingGateway;
import com.portfolio.ragobservability.retrieval.RetrievalException;
import com.portfolio.ragobservability.retrieval.RetrievalService;
import com.portfolio.ragobservability.retrieval.SearchHit;

public final class EvaluationRunner {
    public record Row(GoldenDataset.Query query, List<SearchHit> hits, EvaluationMetrics.Score score,
                      double latencyMs, String errorCode) {
        public Row { hits = List.copyOf(hits); }
    }
    public record Run(Instant startedAt, String datasetSha256, String provider, String model, int dimensions,
                      List<EvaluationCorpus.Document> documents, List<Row> rows, boolean complete,
                      String gitCommit, String gitDirty, GoldenDataset dataset) {}
    private final RetrievalService retrieval;
    private final EmbeddingGateway gateway;
    private final EvaluationCorpus corpus;
    private final EvaluationReport report;

    public EvaluationRunner(RetrievalService retrieval, EmbeddingGateway gateway,
                            EvaluationCorpus corpus, EvaluationReport report) {
        this.retrieval = retrieval; this.gateway = gateway; this.corpus = corpus; this.report = report;
    }

    public int run(Path datasetPath, Path outputRoot, String gitCommit, String gitDirty) {
        Path output = null;
        try {
            GoldenDataset dataset = GoldenDataset.load(datasetPath);
            dataset.validateLocalSources(Path.of("samples/retrieval-lab"));
            System.out.println("Dataset " + dataset.datasetId() + " SHA-256=" + dataset.sha256()
                    + "; expected Query Embedding calls=" + dataset.queries().size() + "; Generation calls=0");
            output = report.prepare(outputRoot);
            List<EvaluationCorpus.Document> documents = corpus.validate(gateway);
            validateDocumentHashes(dataset, documents);
            Map<String, Long> ids = documents.stream().collect(Collectors.toMap(EvaluationCorpus.Document::filename, EvaluationCorpus.Document::id));
            List<Row> rows = new ArrayList<>();
            Instant started = Instant.now();
            boolean complete = true;
            for (GoldenDataset.Query query : dataset.queries()) {
                long start = System.nanoTime();
                try {
                    List<SearchHit> hits = retrieval.search(query.question(), 5, -1.0).results();
                    double elapsed = (System.nanoTime() - start) / 1_000_000.0;
                    if (hits.stream().anyMatch(h -> !ids.containsValue(h.documentId())))
                        throw new IllegalStateException("CORPUS_CHANGED");
                    rows.add(new Row(query, hits, EvaluationMetrics.score(ids.get(query.relevantDocument()), hits), elapsed, null));
                } catch (RuntimeException e) {
                    String code = e instanceof RetrievalException re ? safeCode(re.code()) : "EVALUATION_QUERY_FAILED";
                    rows.add(new Row(query, List.of(), new EvaluationMetrics.Score(null, null, null),
                            (System.nanoTime() - start) / 1_000_000.0, code));
                    complete = false;
                    break;
                }
            }
            report.write(output, new Run(started, dataset.sha256(), gateway.provider(), gateway.model(), gateway.dimensions(),
                    documents, rows, complete, gitCommit, gitDirty, dataset));
            System.out.println("Evaluation " + (complete ? "COMPLETE" : "INCOMPLETE") + ": " + output);
            return complete ? 0 : 1;
        } catch (Exception e) {
            // Never print exception messages or stack traces: JDBC/provider exceptions can contain secrets.
            String code = preflightCode(e);
            if (output != null) {
                try { report.failure(output, code); } catch (Exception ignored) { /* Nonzero exit also covers unwritable output. */ }
            }
            System.err.println(code);
            return 1;
        }
    }

    static void validateDocumentHashes(GoldenDataset dataset, List<EvaluationCorpus.Document> documents) {
        for (var entry : dataset.documents().entrySet()) {
            if (documents.stream().noneMatch(d -> d.filename().equals(entry.getKey()) && d.sha256().equals(entry.getValue())))
                throw new IllegalStateException("CORPUS_HASH_MISMATCH");
        }
    }

    private static String preflightCode(Exception e) {
        String message = e.getMessage();
        if (message == null) return "EVALUATION_PREFLIGHT_OR_REPORT_FAILED";
        return switch (message) {
            case "CORPUS_HASH_MISMATCH", "SOURCE_HASH_MISMATCH", "EMBEDDING_DISABLED", "INVALID_EMBEDDING_DIMENSIONS", "CORPUS_NOT_READY_OR_MODEL_MISMATCH",
                 "CORPUS_MISSING_OR_DUPLICATE_FILENAME", "EXTRA_ELIGIBLE_DOCUMENTS", "INVALID_DATASET",
                 "DATASET_REQUIRES_15_QUERIES", "INVALID_DATASET_ROW", "UNRELATED_MUST_HAVE_NO_EXPECTED_DOCUMENT",
                 "INVALID_EXPECTED_DOCUMENT", "DATASET_REQUIRES_TWO_QUESTIONS_PER_CATEGORY_AND_DOCUMENT",
                 "DATASET_REQUIRES_THREE_UNRELATED_QUESTIONS" -> message;
            default -> "EVALUATION_PREFLIGHT_OR_REPORT_FAILED";
        };
    }

    private static String safeCode(String code) {
        return switch (code) {
            case "EMBEDDING_DISABLED", "EMBEDDING_PROVIDER_FAILED", "EMBEDDING_INVALID_RESPONSE", "RETRIEVAL_FAILED", "VALIDATION_FAILED" -> code;
            default -> "EVALUATION_QUERY_FAILED";
        };
    }
}
