package com.portfolio.ragobservability.evaluation;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import com.portfolio.ragobservability.document.embedding.EmbeddingGateway;

public final class EvaluationCorpus {
    public record Document(long id, String filename, String sha256, long chunkCount) {}
    private final JdbcClient jdbc;
    public EvaluationCorpus(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<Document> validate(EmbeddingGateway gateway) {
        if (!gateway.isEnabled()) throw new IllegalStateException("EMBEDDING_DISABLED");
        if (gateway.dimensions() != 1536) throw new IllegalStateException("INVALID_EMBEDDING_DIMENSIONS");
        List<Document> documents = jdbc.sql("""
                SELECT d.id, d.original_filename, d.content_sha256, d.status, d.embedding_status,
                       COUNT(c.id) AS chunks,
                       COUNT(c.id) FILTER (WHERE c.embedding IS NOT NULL
                           AND c.embedding_provider = :provider AND c.embedding_model = :model
                           AND vector_dims(c.embedding) = :dimensions) AS valid_chunks
                FROM document d LEFT JOIN document_chunk c ON c.document_id = d.id
                WHERE d.original_filename IN (:names)
                GROUP BY d.id ORDER BY d.id
                """)
                .param("names", GoldenDataset.DOCUMENTS).param("provider", gateway.provider())
                .param("model", gateway.model()).param("dimensions", gateway.dimensions())
                .query((rs, row) -> {
                    if (!"READY".equals(rs.getString("status")) || !"READY".equals(rs.getString("embedding_status"))
                            || rs.getLong("chunks") == 0 || rs.getLong("chunks") != rs.getLong("valid_chunks"))
                        throw new IllegalStateException("CORPUS_NOT_READY_OR_MODEL_MISMATCH");
                    return new Document(rs.getLong("id"), rs.getString("original_filename"),
                            rs.getString("content_sha256"), rs.getLong("chunks"));
                }).list();
        Map<String, Long> counts = documents.stream().collect(Collectors.groupingBy(Document::filename, Collectors.counting()));
        if (documents.size() != 3 || !counts.keySet().equals(GoldenDataset.DOCUMENTS)
                || counts.values().stream().anyMatch(n -> n != 1)) throw new IllegalStateException("CORPUS_MISSING_OR_DUPLICATE_FILENAME");
        long extras = jdbc.sql("""
                SELECT COUNT(DISTINCT d.id) FROM document d JOIN document_chunk c ON c.document_id = d.id
                WHERE d.status = 'READY' AND d.embedding_status = 'READY'
                  AND c.embedding IS NOT NULL AND c.embedding_provider = :provider
                  AND c.embedding_model = :model AND vector_dims(c.embedding) = :dimensions
                  AND d.id NOT IN (:ids)
                """).param("provider", gateway.provider()).param("model", gateway.model())
                .param("dimensions", gateway.dimensions()).param("ids", documents.stream().map(Document::id).toList())
                .query(Long.class).single();
        if (extras != 0) throw new IllegalStateException("EXTRA_ELIGIBLE_DOCUMENTS");
        return documents;
    }
}
