package com.portfolio.ragobservability.retrieval;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.portfolio.ragobservability.common.vector.PgVectorFormatter;

@Repository
public class RetrievalRepository {

    private final JdbcClient jdbcClient;

    public RetrievalRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<SearchHit> searchSimilar(
            float[] queryEmbedding,
            String embeddingProvider,
            String embeddingModel,
            int dimensions,
            int topK,
            double minimumSimilarity) {
        return jdbcClient.sql("""
                        WITH eligible_chunks AS (
                            SELECT dc.id AS chunk_id,
                                   dc.document_id,
                                   d.title AS document_title,
                                   dc.chunk_index,
                                   dc.content,
                                   dc.embedding <=> CAST(:queryEmbedding AS vector) AS distance
                            FROM document_chunk AS dc
                            JOIN document AS d ON d.id = dc.document_id
                            WHERE d.status = 'READY'
                              AND d.embedding_status = 'READY'
                              AND dc.embedding IS NOT NULL
                              AND dc.embedding_provider = :embeddingProvider
                              AND dc.embedding_model = :embeddingModel
                              AND vector_dims(dc.embedding) = :dimensions
                        )
                        SELECT chunk_id,
                               document_id,
                               document_title,
                               chunk_index,
                               content,
                               1 - distance AS similarity
                        FROM eligible_chunks
                        WHERE distance <= 1.0 - :minimumSimilarity
                        ORDER BY distance ASC,
                                 document_id ASC,
                                 chunk_index ASC,
                                 chunk_id ASC
                        LIMIT :topK
                        """)
                .param("queryEmbedding", PgVectorFormatter.format(queryEmbedding))
                .param("embeddingProvider", embeddingProvider)
                .param("embeddingModel", embeddingModel)
                .param("dimensions", dimensions)
                .param("minimumSimilarity", minimumSimilarity)
                .param("topK", topK)
                .query((resultSet, rowNumber) -> new SearchHit(
                        resultSet.getLong("chunk_id"),
                        resultSet.getLong("document_id"),
                        resultSet.getString("document_title"),
                        resultSet.getInt("chunk_index"),
                        resultSet.getString("content"),
                        resultSet.getDouble("similarity")))
                .list();
    }
}
