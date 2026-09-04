package com.portfolio.ragobservability.document.chunk;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentChunkRepository {

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public DocumentChunkRepository(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    public void replace(long documentId, List<ChunkDraft> chunks) {
        deleteByDocumentId(documentId);

        SqlParameterSource[] parameters = new SqlParameterSource[chunks.size()];
        for (int index = 0; index < chunks.size(); index++) {
            ChunkDraft chunk = chunks.get(index);
            parameters[index] = new MapSqlParameterSource()
                    .addValue("documentId", documentId)
                    .addValue("chunkIndex", index)
                    .addValue("content", chunk.content())
                    .addValue("tokenCount", chunk.tokenCount());
        }

        namedParameterJdbcTemplate.batchUpdate("""
                INSERT INTO document_chunk (document_id, chunk_index, content, token_count)
                VALUES (:documentId, :chunkIndex, :content, :tokenCount)
                """, parameters);
    }

    public int deleteByDocumentId(long documentId) {
        return jdbcClient.sql("DELETE FROM document_chunk WHERE document_id = :documentId")
                .param("documentId", documentId)
                .update();
    }

    public List<StoredChunk> findByDocumentId(long documentId) {
        return jdbcClient.sql("""
                        SELECT id, document_id, chunk_index, content, token_count,
                               embedding IS NOT NULL AS embedded,
                               embedding_provider, embedding_model, created_at
                        FROM document_chunk
                        WHERE document_id = :documentId
                        ORDER BY chunk_index ASC
                        """)
                .param("documentId", documentId)
                .query(DocumentChunkRepository::mapChunk)
                .list();
    }

    private static StoredChunk mapChunk(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredChunk(
                resultSet.getLong("id"),
                resultSet.getLong("document_id"),
                resultSet.getInt("chunk_index"),
                resultSet.getString("content"),
                resultSet.getInt("token_count"),
                resultSet.getBoolean("embedded"),
                resultSet.getString("embedding_provider"),
                resultSet.getString("embedding_model"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
