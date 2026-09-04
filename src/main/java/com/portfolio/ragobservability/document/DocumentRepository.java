package com.portfolio.ragobservability.document;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.portfolio.ragobservability.document.embedding.EmbeddingStatus;

@Repository
public class DocumentRepository {

    private static final String COLUMNS = """
            id, title, original_filename, source_type, content_sha256, status,
            error_code, error_message, retry_count,
            embedding_status, embedding_error_code, embedding_error_message, embedding_retry_count,
            raw_content, created_at, updated_at
            """;

    private final JdbcClient jdbcClient;

    public DocumentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public StoredDocument insert(
            String title,
            String originalFilename,
            DocumentSourceType sourceType,
            String contentSha256,
            String rawContent) {
        return jdbcClient.sql("""
                        INSERT INTO document (
                            title, original_filename, source_type, content_sha256, status, raw_content
                        )
                        VALUES (:title, :originalFilename, :sourceType, :contentSha256, 'PENDING', :rawContent)
                        RETURNING %s
                        """.formatted(COLUMNS))
                .param("title", title)
                .param("originalFilename", originalFilename)
                .param("sourceType", sourceType.name())
                .param("contentSha256", contentSha256)
                .param("rawContent", rawContent)
                .query(DocumentRepository::mapDocument)
                .single();
    }

    public Optional<StoredDocument> findById(long id) {
        return jdbcClient.sql("SELECT %s FROM document WHERE id = :id".formatted(COLUMNS))
                .param("id", id)
                .query(DocumentRepository::mapDocument)
                .optional();
    }

    public boolean existsByContentSha256(String contentSha256) {
        return jdbcClient.sql("SELECT EXISTS(SELECT 1 FROM document WHERE content_sha256 = :contentSha256)")
                .param("contentSha256", contentSha256)
                .query(Boolean.class)
                .single();
    }

    public Optional<StoredDocument> claimForProcessing(long id) {
        return jdbcClient.sql("""
                        UPDATE document
                        SET status = 'PROCESSING',
                            error_code = NULL,
                            error_message = NULL,
                            retry_count = retry_count + CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id AND status IN ('PENDING', 'FAILED')
                        RETURNING %s
                        """.formatted(COLUMNS))
                .param("id", id)
                .query(DocumentRepository::mapDocument)
                .optional();
    }

    public boolean markReady(long id) {
        return jdbcClient.sql("""
                        UPDATE document
                        SET status = 'READY', error_code = NULL, error_message = NULL,
                            embedding_status = 'PENDING',
                            embedding_error_code = NULL, embedding_error_message = NULL,
                            embedding_retry_count = 0,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id AND status = 'PROCESSING'
                        """)
                .param("id", id)
                .update() == 1;
    }

    public boolean markFailed(long id, String errorCode, String errorMessage) {
        return jdbcClient.sql("""
                        UPDATE document
                        SET status = 'FAILED', error_code = :errorCode, error_message = :errorMessage,
                            embedding_status = 'NOT_STARTED',
                            embedding_error_code = NULL, embedding_error_message = NULL,
                            embedding_retry_count = 0,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id AND status = 'PROCESSING'
                        """)
                .param("id", id)
                .param("errorCode", errorCode)
                .param("errorMessage", errorMessage)
                .update() == 1;
    }

    public List<StoredDocument> findPage(int size, long offset, DocumentStatus status) {
        if (status == null) {
            return jdbcClient.sql("""
                            SELECT %s
                            FROM document
                            ORDER BY created_at DESC, id DESC
                            LIMIT :size OFFSET :offset
                            """.formatted(COLUMNS))
                    .param("size", size)
                    .param("offset", offset)
                    .query(DocumentRepository::mapDocument)
                    .list();
        }

        return jdbcClient.sql("""
                        SELECT %s
                        FROM document
                        WHERE status = :status
                        ORDER BY created_at DESC, id DESC
                        LIMIT :size OFFSET :offset
                        """.formatted(COLUMNS))
                .param("status", status.name())
                .param("size", size)
                .param("offset", offset)
                .query(DocumentRepository::mapDocument)
                .list();
    }

    public long count(DocumentStatus status) {
        if (status == null) {
            return jdbcClient.sql("SELECT COUNT(*) FROM document")
                    .query(Long.class)
                    .single();
        }
        return jdbcClient.sql("SELECT COUNT(*) FROM document WHERE status = :status")
                .param("status", status.name())
                .query(Long.class)
                .single();
    }

    public Optional<StoredDocument> updateTitle(long id, String title) {
        return jdbcClient.sql("""
                        UPDATE document
                        SET title = :title, updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id
                        RETURNING %s
                        """.formatted(COLUMNS))
                .param("id", id)
                .param("title", title)
                .query(DocumentRepository::mapDocument)
                .optional();
    }

    public boolean deleteById(long id) {
        return jdbcClient.sql("DELETE FROM document WHERE id = :id")
                .param("id", id)
                .update() == 1;
    }

    private static StoredDocument mapDocument(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredDocument(
                resultSet.getLong("id"),
                resultSet.getString("title"),
                resultSet.getString("original_filename"),
                DocumentSourceType.valueOf(resultSet.getString("source_type")),
                resultSet.getString("content_sha256"),
                DocumentStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("error_code"),
                resultSet.getString("error_message"),
                resultSet.getInt("retry_count"),
                EmbeddingStatus.valueOf(resultSet.getString("embedding_status")),
                resultSet.getString("embedding_error_code"),
                resultSet.getString("embedding_error_message"),
                resultSet.getInt("embedding_retry_count"),
                resultSet.getString("raw_content"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
