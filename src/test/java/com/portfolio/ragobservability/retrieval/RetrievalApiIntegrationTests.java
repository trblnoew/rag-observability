package com.portfolio.ragobservability.retrieval;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
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
import com.portfolio.ragobservability.common.vector.PgVectorFormatter;
import com.portfolio.ragobservability.document.embedding.EmbeddingGateway;
import com.portfolio.ragobservability.document.embedding.EmbeddingProperties;
import com.portfolio.ragobservability.document.embedding.EmbeddingProviderException;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = {RagObservabilityApplication.class, RetrievalApiIntegrationTests.TestConfig.class},
        properties = {
                "management.tracing.enabled=false",
                "rag.embedding.provider=none",
                "rag.generation.provider=none"
        })
@AutoConfigureMockMvc
class RetrievalApiIntegrationTests {

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
    private QueryEmbeddingGateway gateway;

    @BeforeEach
    void reset() {
        jdbcClient.sql("TRUNCATE TABLE document_chunk, document RESTART IDENTITY CASCADE").update();
        gateway.reset();
    }

    @Test
    void returnsExactCosineRankingThresholdAndMetadata() throws Exception {
        long closest = insertDocument("closest", "READY", "READY", 1);
        insertChunk(closest, 0, "closest content", vector(1.0f, 0.0f), "fake", "fake-1536");
        long second = insertDocument("second", "READY", "READY", 2);
        insertChunk(second, 0, "second content", vector(0.8f, 0.6f), "fake", "fake-1536");
        long excluded = insertDocument("excluded", "READY", "READY", 3);
        insertChunk(excluded, 0, "excluded content", vector(0.0f, 1.0f), "fake", "fake-1536");

        mockMvc.perform(post("/api/v1/search")
                        .contentType("application/json")
                        .content("""
                                {"query":"  database  timeout\\ncheck  ",
                                 "topK":2,
                                 "minimumSimilarity":0.5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("database  timeout\ncheck"))
                .andExpect(jsonPath("$.results", hasSize(2)))
                .andExpect(jsonPath("$.results[0].documentId").value(closest))
                .andExpect(jsonPath("$.results[0].documentTitle").value("closest"))
                .andExpect(jsonPath("$.results[0].similarity").value(1.0))
                .andExpect(jsonPath("$.results[1].documentId").value(second))
                .andExpect(jsonPath("$.results[1].similarity").value(closeTo(0.8, 0.000001)))
                .andExpect(jsonPath("$.results[0].embedding").doesNotExist())
                .andExpect(jsonPath("$.meta.topK").value(2))
                .andExpect(jsonPath("$.meta.minimumSimilarity").value(0.5))
                .andExpect(jsonPath("$.meta.resultCount").value(2))
                .andExpect(jsonPath("$.meta.embeddingProvider").value("fake"))
                .andExpect(jsonPath("$.meta.embeddingModel").value("fake-1536"))
                .andExpect(jsonPath("$.meta.dimensions").value(1536));
    }

    @Test
    void filtersDocumentStatesNullVectorsProviderAndModel() throws Exception {
        long valid = insertDocument("valid", "READY", "READY", 1);
        insertChunk(valid, 0, "valid", vector(1.0f, 0.0f), "fake", "fake-1536");

        long chunkPending = insertDocument("chunk-pending", "PENDING", "READY", 2);
        insertChunk(chunkPending, 0, "chunk-pending", vector(1.0f, 0.0f), "fake", "fake-1536");
        long embeddingPending = insertDocument("embedding-pending", "READY", "PENDING", 3);
        insertChunk(embeddingPending, 0, "embedding-pending", vector(1.0f, 0.0f), "fake", "fake-1536");
        long otherProvider = insertDocument("other-provider", "READY", "READY", 4);
        insertChunk(otherProvider, 0, "other-provider", vector(1.0f, 0.0f), "other", "fake-1536");
        long otherModel = insertDocument("other-model", "READY", "READY", 5);
        insertChunk(otherModel, 0, "other-model", vector(1.0f, 0.0f), "fake", "other-model");
        long nullVector = insertDocument("null-vector", "READY", "READY", 6);
        insertChunk(nullVector, 0, "null-vector", null, null, null);

        mockMvc.perform(post("/api/v1/search")
                        .contentType("application/json")
                        .content("""
                                {"query":"query","topK":20,"minimumSimilarity":-1.0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.results[0].documentId").value(valid));
    }

    @Test
    void usesDeterministicTieBreakAndReturnsEmptyResultsNormally() throws Exception {
        long documentId = insertDocument("ties", "READY", "READY", 1);
        insertChunk(documentId, 1, "second chunk", vector(1.0f, 0.0f), "fake", "fake-1536");
        insertChunk(documentId, 0, "first chunk", vector(1.0f, 0.0f), "fake", "fake-1536");

        mockMvc.perform(post("/api/v1/search")
                        .contentType("application/json")
                        .content("{\"query\":\"query\",\"minimumSimilarity\":1.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(2)))
                .andExpect(jsonPath("$.results[0].chunkIndex").value(0))
                .andExpect(jsonPath("$.results[1].chunkIndex").value(1));

        mockMvc.perform(post("/api/v1/search")
                        .contentType("application/json")
                        .content("{\"query\":\"query\",\"minimumSimilarity\":1.0,\"topK\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(1)));

        jdbcClient.sql("UPDATE document_chunk SET embedding_model = 'old-model'").update();
        mockMvc.perform(post("/api/v1/search")
                        .contentType("application/json")
                        .content("{\"query\":\"query\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(0)))
                .andExpect(jsonPath("$.meta.resultCount").value(0));
    }

    @Test
    void validatesRequestAndRejectsUnknownFields() throws Exception {
        for (String body : List.of(
                "{\"query\":\"   \"}",
                "{\"query\":\"query\",\"topK\":0}",
                "{\"query\":\"query\",\"topK\":21}",
                "{\"query\":\"query\",\"minimumSimilarity\":-1.01}",
                "{\"query\":\"query\",\"minimumSimilarity\":1.01}",
                "{\"query\":\"query\",\"minimumSimilarity\":\"NaN\"}",
                "{\"query\":\"query\",\"minimumSimilarity\":\"Infinity\"}")) {
            mockMvc.perform(post("/api/v1/search")
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        mockMvc.perform(post("/api/v1/search")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "query", "x".repeat(2_001)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/search")
                        .contentType("application/json")
                        .content("{\"query\":\"query\",\"unsupported\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void mapsProviderAndInvalidEmbeddingResponses() throws Exception {
        gateway.fail();
        mockMvc.perform(post("/api/v1/search")
                        .contentType("application/json")
                        .content("{\"query\":\"sensitive question\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EMBEDDING_PROVIDER_FAILED"))
                .andExpect(jsonPath("$.detail").value("Embedding provider request failed"));

        gateway.wrongDimensions();
        mockMvc.perform(post("/api/v1/search")
                        .contentType("application/json")
                        .content("{\"query\":\"sensitive question\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EMBEDDING_INVALID_RESPONSE"));
    }

    @Test
    void completesUploadChunkEmbeddingAndSearchFlow() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "retrieval-flow.txt",
                "text/plain",
                "database timeout recovery guide".getBytes(StandardCharsets.UTF_8));
        byte[] uploadResponse = mockMvc.perform(multipart("/api/v1/documents").file(file))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray();
        long documentId = objectMapper.readTree(uploadResponse).get("id").asLong();

        mockMvc.perform(post("/api/v1/documents/{id}/process", documentId))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/documents/{id}/embeddings", documentId))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/search")
                        .contentType("application/json")
                        .content("{\"query\":\"database connection problem\",\"minimumSimilarity\":0.99}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.results[0].documentId").value(documentId))
                .andExpect(jsonPath("$.results[0].content")
                        .value("database timeout recovery guide"));
    }

    private long insertDocument(
            String title,
            String documentStatus,
            String embeddingStatus,
            int sequence) {
        return jdbcClient.sql("""
                        INSERT INTO document (
                            title, original_filename, source_type, content_sha256,
                            status, raw_content, embedding_status
                        )
                        VALUES (:title, :filename, 'TXT', :sha,
                                :documentStatus, :content, :embeddingStatus)
                        RETURNING id
                        """)
                .param("title", title)
                .param("filename", title + ".txt")
                .param("sha", "%064d".formatted(sequence))
                .param("documentStatus", documentStatus)
                .param("content", title + " content")
                .param("embeddingStatus", embeddingStatus)
                .query(Long.class)
                .single();
    }

    private void insertChunk(
            long documentId,
            int chunkIndex,
            String content,
            float[] vector,
            String provider,
            String model) {
        jdbcClient.sql("""
                        INSERT INTO document_chunk (
                            document_id, chunk_index, content, token_count,
                            embedding, embedding_provider, embedding_model
                        )
                        VALUES (:documentId, :chunkIndex, :content, 2,
                                CAST(:embedding AS vector), :provider, :model)
                        """)
                .param("documentId", documentId)
                .param("chunkIndex", chunkIndex)
                .param("content", content)
                .param("embedding", vector == null ? null : PgVectorFormatter.format(vector))
                .param("provider", provider)
                .param("model", model)
                .update();
    }

    private static float[] vector(float first, float second) {
        float[] vector = new float[EmbeddingProperties.DATABASE_DIMENSIONS];
        vector[0] = first;
        vector[1] = second;
        return vector;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @Primary
        QueryEmbeddingGateway queryEmbeddingGateway() {
            return new QueryEmbeddingGateway();
        }
    }

    static class QueryEmbeddingGateway implements EmbeddingGateway {

        private boolean failing;
        private boolean wrongDimensions;

        void reset() {
            failing = false;
            wrongDimensions = false;
        }

        void fail() {
            failing = true;
            wrongDimensions = false;
        }

        void wrongDimensions() {
            failing = false;
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
            if (failing) {
                throw new EmbeddingProviderException(new IllegalStateException("simulated failure"));
            }
            int dimensions = wrongDimensions ? 10 : EmbeddingProperties.DATABASE_DIMENSIONS;
            return inputs.stream().map(input -> {
                float[] vector = new float[dimensions];
                vector[0] = 1.0f;
                return vector;
            }).toList();
        }
    }
}
