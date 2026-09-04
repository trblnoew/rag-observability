package com.portfolio.ragobservability.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.portfolio.ragobservability.document.chunk.DocumentProcessingTransactions;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "management.tracing.enabled=false",
        "rag.embedding.provider=none",
        "rag.generation.provider=none"
})
@AutoConfigureMockMvc
class DocumentApiIntegrationTests {

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
    private DocumentProcessingTransactions documentProcessingTransactions;

    @BeforeEach
    void clearDocuments() {
        jdbcClient.sql("TRUNCATE TABLE document_chunk, document RESTART IDENTITY CASCADE").update();
    }

    @Test
    void appliesV2AndCreatesAndReadsTextDocument() throws Exception {
        MockMultipartFile file = textFile("C:\\fakepath\\runbook.txt", "connection pool guide");

        MvcResult result = mockMvc.perform(multipart("/api/v1/documents")
                        .file(file)
                        .param("title", "  DB Runbook  "))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/documents/1")))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.title").value("DB Runbook"))
                .andExpect(jsonPath("$.originalFilename").value("runbook.txt"))
                .andExpect(jsonPath("$.sourceType").value("TXT"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.embeddingStatus").value("NOT_STARTED"))
                .andReturn();

        long id = responseId(result);
        Map<String, Object> stored = jdbcClient.sql("""
                        SELECT title, original_filename, source_type, content_sha256, status, raw_content
                        FROM document WHERE id = :id
                        """)
                .param("id", id)
                .query()
                .singleRow();
        assertThat(stored)
                .containsEntry("title", "DB Runbook")
                .containsEntry("original_filename", "runbook.txt")
                .containsEntry("source_type", "TXT")
                .containsEntry("status", "PENDING")
                .containsEntry("raw_content", "connection pool guide");
        assertThat(stored.get("content_sha256").toString()).hasSize(64);

        String nullable = jdbcClient.sql("""
                        SELECT is_nullable FROM information_schema.columns
                        WHERE table_name = 'document' AND column_name = 'raw_content'
                        """)
                .query(String.class)
                .single();
        assertThat(nullable).isEqualTo("NO");

        mockMvc.perform(get("/api/v1/documents/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("connection pool guide"))
                .andExpect(jsonPath("$.contentSha256").isString())
                .andExpect(jsonPath("$.errorCode").doesNotExist())
                .andExpect(jsonPath("$.errorMessage").doesNotExist());
    }

    @Test
    void derivesMarkdownTitleAndReturnsPagedStatusFilteredList() throws Exception {
        upload("first.txt", "first", null);
        upload("second.md", "second", null);
        upload("third.txt", "third", "Third title");

        mockMvc.perform(get("/api/v1/documents")
                        .param("page", "0")
                        .param("size", "2")
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].title").value("Third title"))
                .andExpect(jsonPath("$.items[1].title").value("second"))
                .andExpect(jsonPath("$.items[1].sourceType").value("MARKDOWN"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/api/v1/documents")
                        .param("status", "READY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void updatesOnlyTitleAndHardDeletesDocumentAndChunks() throws Exception {
        long id = upload("guide.txt", "guide content", "Old title");
        jdbcClient.sql("""
                        INSERT INTO document_chunk (document_id, chunk_index, content)
                        VALUES (:documentId, 0, 'temporary chunk')
                        """)
                .param("documentId", id)
                .update();

        mockMvc.perform(patch("/api/v1/documents/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  New title  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New title"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(delete("/api/v1/documents/{id}", id))
                .andExpect(status().isNoContent());

        long documentCount = jdbcClient.sql("SELECT COUNT(*) FROM document WHERE id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
        long chunkCount = jdbcClient.sql("SELECT COUNT(*) FROM document_chunk WHERE document_id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
        assertThat(documentCount).isZero();
        assertThat(chunkCount).isZero();

        mockMvc.perform(get("/api/v1/documents/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void rejectsDuplicateUnsupportedBlankMalformedAndOversizedFiles() throws Exception {
        upload("duplicate.txt", "same content", null);

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(textFile("copy.txt", "same content")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_DUPLICATE"));

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile(
                                "file", "manual.pdf", "application/pdf", "pdf".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_DOCUMENT_TYPE"));

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(textFile("blank.txt", "   \n\t")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT"));

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile(
                                "file", "broken.txt", "text/plain", new byte[] {(byte) 0xC3, 0x28})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT"));

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile(
                                "file",
                                "large.txt",
                                "text/plain",
                                new byte[(int) DocumentService.MAX_FILE_BYTES + 1])))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("DOCUMENT_TOO_LARGE"));
    }

    @Test
    void rejectsInvalidParametersUnknownPatchFieldsAndMissingDocuments() throws Exception {
        long id = upload("guide.txt", "guide", null);

        mockMvc.perform(get("/api/v1/documents").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/v1/documents").param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        mockMvc.perform(patch("/api/v1/documents/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"new\",\"status\":\"READY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        mockMvc.perform(patch("/api/v1/documents/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(delete("/api/v1/documents/{id}", 9999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void processesPendingDocumentAndReturnsOrderedChunksWithoutEmbeddings() throws Exception {
        String content = java.util.stream.IntStream.range(0, 1_000)
                .mapToObj(index -> "SECTION_%04d database timeout runbook. ".formatted(index))
                .collect(java.util.stream.Collectors.joining());
        long id = upload("long-runbook.md", content, "Long runbook");

        mockMvc.perform(get("/api/v1/documents/{id}/chunks", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.items", hasSize(0)));

        MvcResult processResult = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/v1/documents/{id}/process", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.retryCount").value(0))
                .andReturn();
        int chunkCount = objectMapper.readTree(processResult.getResponse().getContentAsByteArray())
                .get("chunkCount")
                .asInt();
        assertThat(chunkCount).isGreaterThan(1);

        mockMvc.perform(get("/api/v1/documents/{id}/chunks", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.items", hasSize(chunkCount)))
                .andExpect(jsonPath("$.items[0].chunkIndex").value(0))
                .andExpect(jsonPath("$.items[0].tokenCount").isNumber())
                .andExpect(jsonPath("$.items[0].embedded").value(false));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/documents/{id}/embeddings", id))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EMBEDDING_DISABLED"));

        long embeddedChunks = jdbcClient.sql("""
                        SELECT COUNT(*) FROM document_chunk
                        WHERE document_id = :documentId
                          AND (embedding IS NOT NULL OR embedding_provider IS NOT NULL OR embedding_model IS NOT NULL)
                        """)
                .param("documentId", id)
                .query(Long.class)
                .single();
        assertThat(embeddedChunks).isZero();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/documents/{id}/process", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_PROCESSABLE"));
    }

    @Test
    void rejectsConcurrentProcessingClaimAndMissingDocument() throws Exception {
        long id = upload("claim.txt", "claim content", null);
        documentProcessingTransactions.claim(id);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/documents/{id}/process", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_PROCESSABLE"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/documents/{id}/process", 9999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/documents/{id}/chunks", 9999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/documents/{id}/embeddings", 9999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void disabledEmbeddingProviderKeepsApplicationUpAndRejectsSearch() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/search")
                        .contentType("application/json")
                        .content("{\"query\":\"database timeout\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EMBEDDING_DISABLED"));
    }

    private long upload(String filename, String content, String title) throws Exception {
        var request = multipart("/api/v1/documents").file(textFile(filename, content));
        if (title != null) {
            request.param("title", title);
        }
        return responseId(mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andReturn());
    }

    private static MockMultipartFile textFile(String filename, String content) {
        String contentType = filename.toLowerCase().endsWith(".md") ? "text/markdown" : "text/plain";
        return new MockMultipartFile(
                "file",
                filename,
                contentType,
                content.getBytes(StandardCharsets.UTF_8));
    }

    private long responseId(MvcResult result) throws Exception {
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return response.get("id").asLong();
    }
}
