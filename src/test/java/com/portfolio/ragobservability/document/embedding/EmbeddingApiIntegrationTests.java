package com.portfolio.ragobservability.document.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.portfolio.ragobservability.RagObservabilityApplication;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = {RagObservabilityApplication.class, EmbeddingApiIntegrationTests.TestConfig.class},
        properties = {
                "management.tracing.enabled=false",
                "rag.embedding.provider=none",
                "rag.generation.provider=none"
        })
@AutoConfigureMockMvc
class EmbeddingApiIntegrationTests {

    private static final DockerImageName PGVECTOR_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PGVECTOR_IMAGE);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ControllableEmbeddingGateway gateway;

    @Autowired
    private EmbeddingTransactions transactions;

    @BeforeEach
    void reset() {
        jdbcClient.sql("TRUNCATE TABLE document_chunk, document RESTART IDENTITY CASCADE").update();
        gateway.succeed();
    }

    @Test
    void appliesV3AndPersistsValidatedPgvectorEmbeddings() throws Exception {
        long id = uploadAndChunk("embedding.txt", "database timeout recovery guide");

        mockMvc.perform(get("/api/v1/documents/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.embeddingStatus").value("PENDING"))
                .andExpect(jsonPath("$.embeddingRetryCount").value(0));

        mockMvc.perform(post("/api/v1/documents/{id}/embeddings", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(id))
                .andExpect(jsonPath("$.embeddingStatus").value("READY"))
                .andExpect(jsonPath("$.embeddedChunkCount").value(1))
                .andExpect(jsonPath("$.provider").value("fake"))
                .andExpect(jsonPath("$.model").value("fake-1536"))
                .andExpect(jsonPath("$.dimensions").value(1536))
                .andExpect(jsonPath("$.retryCount").value(0));

        var stored = jdbcClient.sql("""
                        SELECT COUNT(*) AS chunk_count,
                               COUNT(*) FILTER (WHERE embedding IS NOT NULL) AS embedded_count,
                               MIN(vector_dims(embedding)) AS dimensions,
                               MIN(embedding_provider) AS provider,
                               MIN(embedding_model) AS model
                        FROM document_chunk
                        WHERE document_id = :documentId
                        """)
                .param("documentId", id)
                .query()
                .singleRow();
        assertThat(((Number) stored.get("chunk_count")).longValue()).isEqualTo(1);
        assertThat(((Number) stored.get("embedded_count")).longValue()).isEqualTo(1);
        assertThat(((Number) stored.get("dimensions")).intValue()).isEqualTo(1536);
        assertThat(stored).containsEntry("provider", "fake").containsEntry("model", "fake-1536");

        mockMvc.perform(get("/api/v1/documents/{id}/embeddings", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embeddingStatus").value("READY"))
                .andExpect(jsonPath("$.totalChunkCount").value(1))
                .andExpect(jsonPath("$.embeddedChunkCount").value(1))
                .andExpect(jsonPath("$.dimensions").value(1536));

        mockMvc.perform(get("/api/v1/documents/{id}/chunks", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].embedded").value(true))
                .andExpect(jsonPath("$.items[0].embeddingProvider").value("fake"))
                .andExpect(jsonPath("$.items[0].embeddingModel").value("fake-1536"))
                .andExpect(jsonPath("$.items[0].embedding").doesNotExist());

        String migrationState = jdbcClient.sql("""
                        SELECT success::text
                        FROM flyway_schema_history
                        WHERE version = '3'
                        """)
                .query(String.class)
                .single();
        assertThat(migrationState).isEqualTo("true");
    }

    @Test
    void providerFailureLeavesNoPartialVectorsAndFailedDocumentCanRetry() throws Exception {
        long id = upload("retry-embedding.txt", "retry embedding source");
        markReadyWithChunks(id, 33);
        gateway.failOnCall(2);

        mockMvc.perform(post("/api/v1/documents/{id}/embeddings", id))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EMBEDDING_PROVIDER_FAILED"));

        assertThat(embeddedCount(id)).isZero();
        mockMvc.perform(get("/api/v1/documents/{id}/embeddings", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embeddingStatus").value("FAILED"))
                .andExpect(jsonPath("$.retryCount").value(0))
                .andExpect(jsonPath("$.errorCode").value("EMBEDDING_PROVIDER_FAILED"));

        gateway.succeed();
        mockMvc.perform(post("/api/v1/documents/{id}/embeddings", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embeddingStatus").value("READY"))
                .andExpect(jsonPath("$.embeddedChunkCount").value(33))
                .andExpect(jsonPath("$.retryCount").value(1));

        assertThat(embeddedCount(id)).isEqualTo(33);
        mockMvc.perform(get("/api/v1/documents/{id}/embeddings", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").doesNotExist())
                .andExpect(jsonPath("$.errorMessage").doesNotExist());
    }

    @Test
    void rejectsInvalidVectorWithoutPartialPersistence() throws Exception {
        long id = uploadAndChunk("wrong-dimension.txt", "wrong dimension source");
        gateway.returnWrongDimensions();

        mockMvc.perform(post("/api/v1/documents/{id}/embeddings", id))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EMBEDDING_INVALID_RESPONSE"));

        assertThat(embeddedCount(id)).isZero();
        mockMvc.perform(get("/api/v1/documents/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.embeddingStatus").value("FAILED"))
                .andExpect(jsonPath("$.embeddingErrorCode").value("EMBEDDING_INVALID_RESPONSE"));
    }

    @Test
    void rejectsNotChunkedCompletedConcurrentAndMissingDocuments() throws Exception {
        long pendingId = upload("pending.txt", "pending source");
        mockMvc.perform(post("/api/v1/documents/{id}/embeddings", pendingId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_CHUNKED"));

        long processingId = uploadAndChunk("processing.txt", "processing source");
        transactions.claim(processingId);
        mockMvc.perform(post("/api/v1/documents/{id}/embeddings", processingId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMBEDDING_NOT_PROCESSABLE"));

        long readyId = uploadAndChunk("ready.txt", "ready source");
        mockMvc.perform(post("/api/v1/documents/{id}/embeddings", readyId))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/documents/{id}/embeddings", readyId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMBEDDING_NOT_PROCESSABLE"));

        mockMvc.perform(post("/api/v1/documents/{id}/embeddings", 9999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/documents/{id}/embeddings", 9999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void hardDeleteCascadesEmbeddedChunks() throws Exception {
        long id = uploadAndChunk("delete-embedding.txt", "delete source");
        mockMvc.perform(post("/api/v1/documents/{id}/embeddings", id))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/documents/{id}", id))
                .andExpect(status().isNoContent());
        assertThat(embeddedCount(id)).isZero();
    }

    @Test
    void statusAggregationDoesNotReportMixedProviderOrModelAsSingleConfiguration() throws Exception {
        long id = upload("mixed-provider.txt", "mixed provider source");
        markReadyWithChunks(id, 2);
        mockMvc.perform(post("/api/v1/documents/{id}/embeddings", id))
                .andExpect(status().isOk());
        jdbcClient.sql("""
                        UPDATE document_chunk
                        SET embedding_provider = 'another-provider',
                            embedding_model = 'another-model'
                        WHERE document_id = :documentId AND chunk_index = 1
                        """)
                .param("documentId", id)
                .update();

        mockMvc.perform(get("/api/v1/documents/{id}/embeddings", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalChunkCount").value(2))
                .andExpect(jsonPath("$.embeddedChunkCount").value(2))
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andExpect(jsonPath("$.model").doesNotExist())
                .andExpect(jsonPath("$.dimensions").value(1536));
    }

    private long uploadAndChunk(String filename, String content) throws Exception {
        long id = upload(filename, content);
        mockMvc.perform(post("/api/v1/documents/{id}/process", id))
                .andExpect(status().isOk());
        return id;
    }

    private long upload(String filename, String content) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                filename,
                "text/plain",
                content.getBytes(StandardCharsets.UTF_8));
        byte[] response = mockMvc.perform(multipart("/api/v1/documents").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.embeddingStatus").value("NOT_STARTED"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private void markReadyWithChunks(long documentId, int count) {
        jdbcClient.sql("""
                        UPDATE document
                        SET status = 'READY', embedding_status = 'PENDING'
                        WHERE id = :documentId
                        """)
                .param("documentId", documentId)
                .update();
        for (int index = 0; index < count; index++) {
            jdbcClient.sql("""
                            INSERT INTO document_chunk (document_id, chunk_index, content, token_count)
                            VALUES (:documentId, :chunkIndex, :content, 2)
                            """)
                    .param("documentId", documentId)
                    .param("chunkIndex", index)
                    .param("content", "chunk-" + index)
                    .update();
        }
    }

    private long embeddedCount(long documentId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM document_chunk
                        WHERE document_id = :documentId AND embedding IS NOT NULL
                        """)
                .param("documentId", documentId)
                .query(Long.class)
                .single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @Primary
        ControllableEmbeddingGateway controllableEmbeddingGateway() {
            return new ControllableEmbeddingGateway();
        }
    }

    static class ControllableEmbeddingGateway implements EmbeddingGateway {

        private int callCount;
        private int failingCall = -1;
        private boolean wrongDimensions;

        void succeed() {
            callCount = 0;
            failingCall = -1;
            wrongDimensions = false;
        }

        void failOnCall(int call) {
            callCount = 0;
            failingCall = call;
        }

        void returnWrongDimensions() {
            wrongDimensions = true;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String provider() {
            return "fake";
        }

        @Override
        public String model() {
            return "fake-1536";
        }

        @Override
        public int dimensions() {
            return EmbeddingProperties.DATABASE_DIMENSIONS;
        }

        @Override
        public List<float[]> embed(List<String> inputs) {
            callCount++;
            if (callCount == failingCall) {
                throw new EmbeddingProviderException(new IllegalStateException("simulated provider failure"));
            }
            int dimensions = wrongDimensions ? 10 : EmbeddingProperties.DATABASE_DIMENSIONS;
            return inputs.stream().map(input -> {
                float[] vector = new float[dimensions];
                vector[0] = Math.max(1, Math.abs(input.hashCode() % 1000));
                return vector;
            }).toList();
        }
    }
}
