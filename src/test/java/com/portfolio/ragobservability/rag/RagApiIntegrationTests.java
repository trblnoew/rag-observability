package com.portfolio.ragobservability.rag;

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
import com.portfolio.ragobservability.document.embedding.EmbeddingGateway;
import com.portfolio.ragobservability.document.embedding.EmbeddingProperties;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = {RagObservabilityApplication.class, RagApiIntegrationTests.TestConfig.class},
        properties = {
                "management.tracing.enabled=false",
                "rag.embedding.provider=none",
                "rag.generation.provider=none"
        })
@AutoConfigureMockMvc
class RagApiIntegrationTests {

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
    private FakeGenerationGateway generationGateway;

    @BeforeEach
    void reset() {
        jdbcClient.sql("TRUNCATE TABLE document_chunk, document RESTART IDENTITY CASCADE").update();
        generationGateway.reset();
    }

    @Test
    void completesUploadChunkEmbeddingRetrievalAndAnswerFlow() throws Exception {
        long documentId = createReadyDocument();

        mockMvc.perform(post("/api/v1/answers")
                        .contentType("application/json")
                        .content("""
                                {"question":"  database timeout  ",
                                 "topK":3,
                                 "minimumSimilarity":0.99}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question").value("database timeout"))
                .andExpect(jsonPath("$.answer").value("Check the pool [S1]"))
                .andExpect(jsonPath("$.sources", hasSize(1)))
                .andExpect(jsonPath("$.sources[0].sourceId").value("S1"))
                .andExpect(jsonPath("$.sources[0].documentId").value(documentId))
                .andExpect(jsonPath("$.sources[0].documentTitle").value("rag-flow"))
                .andExpect(jsonPath("$.sources[0].chunkIndex").value(0))
                .andExpect(jsonPath("$.sources[0].excerpt")
                        .value("database timeout recovery guide"))
                .andExpect(jsonPath("$.sources[0].embedding").doesNotExist())
                .andExpect(jsonPath("$.meta.topK").value(3))
                .andExpect(jsonPath("$.meta.minimumSimilarity").value(0.99))
                .andExpect(jsonPath("$.meta.sourceCount").value(1))
                .andExpect(jsonPath("$.meta.embeddingProvider").value("fake"))
                .andExpect(jsonPath("$.meta.embeddingModel").value("fake-1536"))
                .andExpect(jsonPath("$.meta.dimensions").value(1536))
                .andExpect(jsonPath("$.meta.generationProvider").value("fake"))
                .andExpect(jsonPath("$.meta.generationModel").value("fake-chat"))
                .andExpect(jsonPath("$.meta.generationCalled").value(true))
                .andExpect(jsonPath("$.meta.inputTokens").value(40))
                .andExpect(jsonPath("$.meta.outputTokens").value(8));
    }

    @Test
    void returnsNoEvidenceWithoutGenerationCall() throws Exception {
        long documentId = createReadyDocument();
        jdbcClient.sql("UPDATE document SET embedding_status = 'PENDING' WHERE id = :id")
                .param("id", documentId)
                .update();

        mockMvc.perform(post("/api/v1/answers")
                        .contentType("application/json")
                        .content("{\"question\":\"unrelated question\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(RagService.NO_EVIDENCE_ANSWER))
                .andExpect(jsonPath("$.sources", hasSize(0)))
                .andExpect(jsonPath("$.meta.minimumSimilarity").value(0.5))
                .andExpect(jsonPath("$.meta.generationCalled").value(false))
                .andExpect(jsonPath("$.meta.inputTokens").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.meta.outputTokens").value(org.hamcrest.Matchers.nullValue()));

        org.assertj.core.api.Assertions.assertThat(generationGateway.calls()).isZero();
    }

    @Test
    void mapsGenerationDisabledProviderFailureAndInvalidCitation() throws Exception {
        generationGateway.disable();
        mockMvc.perform(post("/api/v1/answers")
                        .contentType("application/json")
                        .content("{\"question\":\"question\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GENERATION_DISABLED"));

        createReadyDocument();
        generationGateway.fail();
        mockMvc.perform(post("/api/v1/answers")
                        .contentType("application/json")
                        .content("{\"question\":\"question\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("GENERATION_PROVIDER_FAILED"))
                .andExpect(jsonPath("$.detail").value("Generation provider request failed"));

        generationGateway.invalidCitation();
        mockMvc.perform(post("/api/v1/answers")
                        .contentType("application/json")
                        .content("{\"question\":\"question\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("GENERATION_INVALID_RESPONSE"));
    }

    @Test
    void validatesRequestAndRejectsUnknownFields() throws Exception {
        for (String body : List.of(
                "{\"question\":\"   \"}",
                "{\"question\":\"query\",\"topK\":0}",
                "{\"question\":\"query\",\"topK\":21}",
                "{\"question\":\"query\",\"minimumSimilarity\":-1.01}",
                "{\"question\":\"query\",\"minimumSimilarity\":1.01}",
                "{\"question\":\"query\",\"minimumSimilarity\":\"NaN\"}")) {
            mockMvc.perform(post("/api/v1/answers")
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        mockMvc.perform(post("/api/v1/answers")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "question", "x".repeat(2_001)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/answers")
                        .contentType("application/json")
                        .content("{\"question\":\"query\",\"unsupported\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    private long createReadyDocument() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "rag-flow.txt",
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
        return documentId;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @Primary
        QueryEmbeddingGateway queryEmbeddingGateway() {
            return new QueryEmbeddingGateway();
        }

        @Bean
        @Primary
        FakeGenerationGateway fakeGenerationGateway() {
            return new FakeGenerationGateway();
        }
    }

    static class QueryEmbeddingGateway implements EmbeddingGateway {

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
            return inputs.stream().map(input -> {
                float[] vector = new float[EmbeddingProperties.DATABASE_DIMENSIONS];
                vector[0] = 1.0f;
                return vector;
            }).toList();
        }
    }

    static class FakeGenerationGateway implements GenerationGateway {

        private Mode mode = Mode.VALID;
        private int calls;

        void reset() {
            mode = Mode.VALID;
            calls = 0;
        }

        void disable() {
            mode = Mode.DISABLED;
        }

        void fail() {
            mode = Mode.FAIL;
        }

        void invalidCitation() {
            mode = Mode.INVALID_CITATION;
        }

        int calls() {
            return calls;
        }

        @Override
        public boolean isEnabled() {
            return mode != Mode.DISABLED;
        }

        @Override
        public String provider() {
            return "fake";
        }

        @Override
        public String model() {
            return "fake-chat";
        }

        @Override
        public GenerationResult generate(GenerationPrompt prompt) {
            calls++;
            if (mode == Mode.FAIL) {
                throw new GenerationProviderException(new IllegalStateException("secret"));
            }
            if (mode == Mode.INVALID_CITATION) {
                return new GenerationResult("Invented source [S9]", null, null);
            }
            return new GenerationResult("Check the pool [S1]", 40, 8);
        }

        private enum Mode {
            VALID,
            DISABLED,
            FAIL,
            INVALID_CITATION
        }
    }
}
