package com.portfolio.ragobservability.document.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class EmbeddingStateMigrationTests {

    private static final DockerImageName PGVECTOR_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PGVECTOR_IMAGE);

    @Test
    void upgradesExistingV2DocumentsToTheCorrectEmbeddingState() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("2")
                .load()
                .migrate();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        long fullyEmbedded = insertDocument(jdbcTemplate, 1);
        insertChunk(jdbcTemplate, fullyEmbedded, 0, vectorLiteral());

        long missingEmbedding = insertDocument(jdbcTemplate, 2);
        insertChunk(jdbcTemplate, missingEmbedding, 0, null);

        long partiallyEmbedded = insertDocument(jdbcTemplate, 3);
        insertChunk(jdbcTemplate, partiallyEmbedded, 0, vectorLiteral());
        insertChunk(jdbcTemplate, partiallyEmbedded, 1, null);

        long withoutChunks = insertDocument(jdbcTemplate, 4);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        Map<Long, String> statuses = jdbcTemplate.query(
                        "SELECT id, embedding_status FROM document",
                        (resultSet, rowNumber) -> Map.entry(
                                resultSet.getLong("id"),
                                resultSet.getString("embedding_status")))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(statuses)
                .containsEntry(fullyEmbedded, "READY")
                .containsEntry(missingEmbedding, "PENDING")
                .containsEntry(partiallyEmbedded, "PENDING")
                .containsEntry(withoutChunks, "NOT_STARTED");

        Integer nonZeroRetryCounts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document WHERE embedding_retry_count <> 0",
                Integer.class);
        assertThat(nonZeroRetryCounts).isZero();
        Boolean migrationSucceeded = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '3'",
                Boolean.class);
        assertThat(migrationSucceeded).isTrue();

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE document SET embedding_status = 'UNKNOWN' WHERE id = ?",
                        fullyEmbedded))
                .isInstanceOf(DataAccessException.class);
    }

    private static long insertDocument(JdbcTemplate jdbcTemplate, int sequence) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO document (
                            title, original_filename, source_type, content_sha256,
                            status, raw_content
                        )
                        VALUES (?, ?, 'TXT', ?, 'READY', ?)
                        RETURNING id
                        """,
                Long.class,
                "document-" + sequence,
                "document-" + sequence + ".txt",
                "%064d".formatted(sequence),
                "document content " + sequence);
    }

    private static void insertChunk(
            JdbcTemplate jdbcTemplate,
            long documentId,
            int chunkIndex,
            String embedding) {
        jdbcTemplate.update("""
                        INSERT INTO document_chunk (
                            document_id, chunk_index, content, token_count,
                            embedding_provider, embedding_model, embedding
                        )
                        VALUES (?, ?, ?, 2, ?, ?, CAST(? AS vector))
                        """,
                documentId,
                chunkIndex,
                "chunk-" + chunkIndex,
                embedding == null ? null : "legacy-provider",
                embedding == null ? null : "legacy-model",
                embedding);
    }

    private static String vectorLiteral() {
        return java.util.stream.IntStream.range(0, EmbeddingProperties.DATABASE_DIMENSIONS)
                .mapToObj(index -> index == 0 ? "1" : "0")
                .collect(Collectors.joining(",", "[", "]"));
    }
}
