package com.portfolio.ragobservability.document.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;

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
        classes = {RagObservabilityApplication.class, DocumentProcessingFailureIntegrationTests.TestConfig.class},
        properties = {
                "management.tracing.enabled=false",
                "rag.embedding.provider=none",
                "rag.generation.provider=none"
        })
@AutoConfigureMockMvc
class DocumentProcessingFailureIntegrationTests {

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
    private ControllableDocumentChunker chunker;

    @BeforeEach
    void reset() {
        jdbcClient.sql("TRUNCATE TABLE document_chunk, document RESTART IDENTITY CASCADE").update();
        chunker.succeedWith(List.of(new ChunkDraft("default", 1)));
    }

    @Test
    void unexpectedFailureRemovesChunksAndFailedDocumentCanRetry() throws Exception {
        long id = upload("retry.txt", "retry source content");
        jdbcClient.sql("""
                        INSERT INTO document_chunk (document_id, chunk_index, content, token_count)
                        VALUES (:documentId, 0, 'stale chunk', 2)
                        """)
                .param("documentId", id)
                .update();
        chunker.failUnexpectedly();

        mockMvc.perform(post("/api/v1/documents/{id}/process", id))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("CHUNKING_FAILED"));

        mockMvc.perform(get("/api/v1/documents/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("CHUNKING_FAILED"))
                .andExpect(jsonPath("$.retryCount").value(0));
        assertThat(chunkCount(id)).isZero();

        chunker.succeedWith(List.of(new ChunkDraft("recovered chunk", 2)));
        mockMvc.perform(post("/api/v1/documents/{id}/process", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.chunkCount").value(1))
                .andExpect(jsonPath("$.retryCount").value(1));

        mockMvc.perform(get("/api/v1/documents/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.errorCode").doesNotExist())
                .andExpect(jsonPath("$.errorMessage").doesNotExist())
                .andExpect(jsonPath("$.retryCount").value(1));
        mockMvc.perform(get("/api/v1/documents/{id}/chunks", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].content").value("recovered chunk"));
    }

    @Test
    void chunkLimitFailurePersistsFailedStateWithoutPartialChunks() throws Exception {
        long id = upload("large.txt", "large source content");
        chunker.succeedWith(IntStream.rangeClosed(0, 500)
                .mapToObj(index -> new ChunkDraft("chunk-" + index, 2))
                .toList());

        mockMvc.perform(post("/api/v1/documents/{id}/process", id))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CHUNK_LIMIT_EXCEEDED"));

        mockMvc.perform(get("/api/v1/documents/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("CHUNK_LIMIT_EXCEEDED"));
        assertThat(chunkCount(id)).isZero();
    }

    private long upload(String filename, String content) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                filename,
                "text/plain",
                content.getBytes(StandardCharsets.UTF_8));
        byte[] response = mockMvc.perform(multipart("/api/v1/documents").file(file))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private long chunkCount(long documentId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM document_chunk WHERE document_id = :documentId")
                .param("documentId", documentId)
                .query(Long.class)
                .single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @Primary
        ControllableDocumentChunker controllableDocumentChunker() {
            return new ControllableDocumentChunker();
        }
    }

    static class ControllableDocumentChunker implements DocumentChunker {

        private List<ChunkDraft> result = List.of(new ChunkDraft("default", 1));
        private boolean fail;

        void succeedWith(List<ChunkDraft> result) {
            this.result = List.copyOf(result);
            this.fail = false;
        }

        void failUnexpectedly() {
            this.fail = true;
        }

        @Override
        public List<ChunkDraft> split(String content) {
            if (fail) {
                throw new IllegalStateException("simulated failure");
            }
            return result;
        }
    }
}
