package com.portfolio.ragobservability.document.embedding;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.portfolio.ragobservability.common.vector.PgVectorFormatter;

@Repository
public class DocumentEmbeddingRepository {

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public DocumentEmbeddingRepository(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    public Optional<EmbeddingClaim> claim(long documentId) {
        return jdbcClient.sql("""
                        UPDATE document
                        SET embedding_status = 'PROCESSING',
                            embedding_error_code = NULL,
                            embedding_error_message = NULL,
                            embedding_retry_count = embedding_retry_count
                                + CASE WHEN embedding_status = 'FAILED' THEN 1 ELSE 0 END,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :documentId
                          AND status = 'READY'
                          AND embedding_status IN ('PENDING', 'FAILED')
                        RETURNING id, embedding_retry_count
                        """)
                .param("documentId", documentId)
                .query((resultSet, rowNumber) -> new EmbeddingClaim(
                        resultSet.getLong("id"),
                        resultSet.getInt("embedding_retry_count")))
                .optional();
    }

    public boolean existsDocument(long documentId) {
        return jdbcClient.sql("SELECT EXISTS(SELECT 1 FROM document WHERE id = :documentId)")
                .param("documentId", documentId)
                .query(Boolean.class)
                .single();
    }

    public Optional<EmbeddingStatusSnapshot> findStatus(long documentId) {
        return jdbcClient.sql("""
                        SELECT d.id,
                               d.embedding_status,
                               d.embedding_retry_count,
                               d.embedding_error_code,
                               d.embedding_error_message,
                               COUNT(c.id) AS total_chunk_count,
                               COUNT(c.embedding) AS embedded_chunk_count,
                               CASE
                                   WHEN COUNT(c.embedding) > 0
                                    AND COUNT(c.embedding_provider) FILTER (WHERE c.embedding IS NOT NULL)
                                        = COUNT(c.embedding)
                                    AND COUNT(DISTINCT c.embedding_provider)
                                        FILTER (WHERE c.embedding IS NOT NULL) = 1
                                   THEN MIN(c.embedding_provider) FILTER (WHERE c.embedding IS NOT NULL)
                               END AS provider,
                               CASE
                                   WHEN COUNT(c.embedding) > 0
                                    AND COUNT(c.embedding_model) FILTER (WHERE c.embedding IS NOT NULL)
                                        = COUNT(c.embedding)
                                    AND COUNT(DISTINCT c.embedding_model)
                                        FILTER (WHERE c.embedding IS NOT NULL) = 1
                                   THEN MIN(c.embedding_model) FILTER (WHERE c.embedding IS NOT NULL)
                               END AS model,
                               CASE
                                   WHEN COUNT(c.embedding) > 0
                                    AND COUNT(DISTINCT vector_dims(c.embedding)) = 1
                                   THEN MIN(vector_dims(c.embedding))
                               END AS dimensions
                        FROM document AS d
                        LEFT JOIN document_chunk AS c ON c.document_id = d.id
                        WHERE d.id = :documentId
                        GROUP BY d.id, d.embedding_status, d.embedding_retry_count,
                                 d.embedding_error_code, d.embedding_error_message
                        """)
                .param("documentId", documentId)
                .query((resultSet, rowNumber) -> new EmbeddingStatusSnapshot(
                        resultSet.getLong("id"),
                        EmbeddingStatus.valueOf(resultSet.getString("embedding_status")),
                        resultSet.getInt("total_chunk_count"),
                        resultSet.getInt("embedded_chunk_count"),
                        resultSet.getString("provider"),
                        resultSet.getString("model"),
                        resultSet.getObject("dimensions", Integer.class),
                        resultSet.getInt("embedding_retry_count"),
                        resultSet.getString("embedding_error_code"),
                        resultSet.getString("embedding_error_message")))
                .optional();
    }

    public List<EmbeddableChunk> findChunks(long documentId) {
        return jdbcClient.sql("""
                        SELECT id, chunk_index, content
                        FROM document_chunk
                        WHERE document_id = :documentId
                        ORDER BY chunk_index ASC
                        """)
                .param("documentId", documentId)
                .query((resultSet, rowNumber) -> new EmbeddableChunk(
                        resultSet.getLong("id"),
                        resultSet.getInt("chunk_index"),
                        resultSet.getString("content")))
                .list();
    }

    public int clearEmbeddings(long documentId) {
        return jdbcClient.sql("""
                        UPDATE document_chunk
                        SET embedding = NULL, embedding_provider = NULL, embedding_model = NULL
                        WHERE document_id = :documentId
                          AND (embedding IS NOT NULL
                               OR embedding_provider IS NOT NULL
                               OR embedding_model IS NOT NULL)
                        """)
                .param("documentId", documentId)
                .update();
    }

    public void saveAll(
            List<ChunkEmbedding> embeddings,
            String provider,
            String model) {
        SqlParameterSource[] parameters = embeddings.stream()
                .map(embedding -> new MapSqlParameterSource()
                        .addValue("chunkId", embedding.chunkId())
                        .addValue("embedding", PgVectorFormatter.format(embedding.vector()))
                        .addValue("provider", provider)
                        .addValue("model", model))
                .toArray(SqlParameterSource[]::new);

        int[] updateCounts = namedParameterJdbcTemplate.batchUpdate("""
                UPDATE document_chunk
                SET embedding = CAST(:embedding AS vector),
                    embedding_provider = :provider,
                    embedding_model = :model
                WHERE id = :chunkId
                """, parameters);
        if (updateCounts.length != embeddings.size()) {
            throw new IllegalStateException("Not all chunk embeddings were persisted");
        }
        for (int updateCount : updateCounts) {
            if (updateCount != 1) {
                throw new IllegalStateException("A chunk embedding could not be persisted");
            }
        }
    }

    public boolean markReady(long documentId) {
        return jdbcClient.sql("""
                        UPDATE document
                        SET embedding_status = 'READY',
                            embedding_error_code = NULL,
                            embedding_error_message = NULL,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :documentId AND embedding_status = 'PROCESSING'
                        """)
                .param("documentId", documentId)
                .update() == 1;
    }

    public boolean markFailed(long documentId, String errorCode, String errorMessage) {
        return jdbcClient.sql("""
                        UPDATE document
                        SET embedding_status = 'FAILED',
                            embedding_error_code = :errorCode,
                            embedding_error_message = :errorMessage,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :documentId AND embedding_status = 'PROCESSING'
                        """)
                .param("documentId", documentId)
                .param("errorCode", errorCode)
                .param("errorMessage", errorMessage)
                .update() == 1;
    }

}
