package com.portfolio.ragobservability.observability;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import com.portfolio.ragobservability.RagObservabilityApplication;
import com.portfolio.ragobservability.document.embedding.*;
import com.portfolio.ragobservability.rag.*;
import com.portfolio.ragobservability.retrieval.*;

@AutoConfigureObservability
@org.testcontainers.junit.jupiter.Testcontainers(disabledWithoutDocker = true)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {RagObservabilityApplication.class, TracingHttpTests.Config.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "rag.embedding.provider=none", "rag.generation.provider=none", "spring.ai.model.embedding=none",
        "spring.ai.model.chat=none", "management.tracing.enabled=true", "management.tracing.sampling.probability=1",
        "spring.flyway.enabled=true"})
class TracingHttpTests {
    @org.testcontainers.junit.jupiter.Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static final org.testcontainers.containers.PostgreSQLContainer<?> postgres = new org.testcontainers.containers.PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-trixie").asCompatibleSubstituteFor("postgres"));
    static final InMemorySpanExporter MEMORY = InMemorySpanExporter.create();
    @Autowired TestRestTemplate http;
    @Autowired SdkTracerProvider sdk;
    @Autowired RagTracing tracing;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    @MockitoBean EmbeddingGateway embedding;
    @MockitoBean GenerationGateway generation;
    @MockitoBean RetrievalRepository repository;

    @BeforeEach void setup() {
        sdk.forceFlush().join(5, TimeUnit.SECONDS); MEMORY.reset();
        when(embedding.isEnabled()).thenReturn(true);
        when(embedding.provider()).thenReturn("fake"); when(embedding.model()).thenReturn("fake-1536");
        when(embedding.dimensions()).thenReturn(1536);
        float[] vector = new float[1536]; vector[0] = 1;
        when(embedding.embed(anyList())).thenReturn(List.of(vector));
        when(generation.isEnabled()).thenReturn(true);
        when(generation.provider()).thenReturn("fake"); when(generation.model()).thenReturn("fake-chat");
        when(generation.generate(any())).thenReturn(new GenerationResult("SECRET_ANSWER [S1]", 10, 5));
        when(repository.searchSimilar(any(), anyString(), anyString(), anyInt(), anyInt(), anyDouble()))
                .thenReturn(List.of(new SearchHit(1, 1, "SECRET_TITLE", 0, "SECRET_CHUNK", .8)));
    }
    ResponseEntity<String> answer() { return http.postForEntity("/api/v1/answers", Map.of("question", "SECRET_QUESTION"), String.class); }
    List<SpanData> spans(ResponseEntity<String> response) {
        sdk.forceFlush().join(5, TimeUnit.SECONDS);
        String traceId = response.getHeaders().getFirst("X-Trace-Id");
        assertThat(traceId).matches("[a-f0-9]{32}");
        return MEMORY.getFinishedSpanItems().stream().filter(s -> s.getTraceId().equals(traceId)).toList();
    }
    SpanData named(List<SpanData> spans, String name) { return spans.stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow(); }
    String tag(SpanData span, String key) { return span.getAttributes().get(AttributeKey.stringKey(key)); }
    void clean(List<SpanData> spans) {
        for (var s : spans) {
            assertThat(s.getEndEpochNanos()).isGreaterThanOrEqualTo(s.getStartEpochNanos());
            assertThat(s.getAttributes().toString() + s.getEvents() + s.getStatus()).doesNotContain("SECRET_");
        }
        assertThat(tracing.currentTraceId()).isNull();
    }
    @Test void answerHasCorrectHierarchyUsageAndSafeMetadata() {
        var response = answer(); assertThat(response.getStatusCode().value()).isEqualTo(200);
        var spans = spans(response);
        assertThat(spans.stream().filter(s -> s.getName().startsWith("rag."))).hasSize(7);
        var root = named(spans, "rag.answer");
        assertThat(root.getParentSpanId()).isEqualTo(spans.stream().filter(s -> s.getKind() == SpanKind.SERVER).findFirst().orElseThrow().getSpanId());
        for (String name : List.of("rag.retrieval", "rag.context_build", "rag.generation", "rag.citation_validation"))
            assertThat(named(spans, name).getParentSpanId()).isEqualTo(root.getSpanId());
        for (String name : List.of("rag.query_embedding", "rag.vector_search"))
            assertThat(named(spans, name).getParentSpanId()).isEqualTo(named(spans, "rag.retrieval").getSpanId());
        assertThat(tag(named(spans, "rag.generation"), "rag.generation.input_tokens")).isEqualTo("10");
        assertThat(root.getResource().getAttribute(AttributeKey.stringKey("service.name"))).isEqualTo("rag-observability");
        clean(spans);
    }
    @Test void healthIsUpWithoutApiKey() {
        var response = http.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
    @Test void directServiceWithRealPgvectorCreatesRootAndCleansScope() {
        jdbc.sql("TRUNCATE document_chunk, document RESTART IDENTITY CASCADE").update();
        jdbc.sql("""
                INSERT INTO document(title,original_filename,source_type,content_sha256,status,raw_content,embedding_status)
                VALUES ('SECRET_TITLE','sample.md','MARKDOWN',:hash,'READY','SECRET_CHUNK','READY')
                """).param("hash", "a".repeat(64)).update();
        float[] vector = new float[1536]; vector[0] = 1;
        jdbc.sql("""
                INSERT INTO document_chunk(document_id,chunk_index,content,token_count,embedding,embedding_provider,embedding_model)
                VALUES (1,0,'SECRET_CHUNK',3,CAST(:vector AS vector),'fake','fake-1536')
                """).param("vector", com.portfolio.ragobservability.common.vector.PgVectorFormatter.format(vector)).update();
        var service = new RetrievalService(embedding, new EmbeddingVectorValidator(), new RetrievalRepository(jdbc), new RetrievalProperties(), tracing);
        var result = service.search("SECRET_QUESTION", 5, -1.0);
        assertThat(result.results()).hasSize(1);
        assertThat(result.results().getFirst().similarity()).isEqualTo(1.0);
        sdk.forceFlush().join(5, TimeUnit.SECONDS);
        var spans = MEMORY.getFinishedSpanItems();
        assertThat(named(spans, "rag.retrieval").getParentSpanId()).isEqualTo("0000000000000000");
        clean(spans);
    }
    @Test void emptyResultsSkipGenerationAndRemainSuccessful() {
        when(repository.searchSimilar(any(), anyString(), anyString(), anyInt(), anyInt(), anyDouble())).thenReturn(List.of());
        var response = answer(); assertThat(response.getStatusCode().value()).isEqualTo(200);
        var spans = spans(response);
        assertThat(spans.stream().filter(s -> s.getName().startsWith("rag."))).hasSize(4);
        assertThat(tag(named(spans, "rag.answer"), "rag.outcome")).isEqualTo("no_evidence");
        assertThat(tag(named(spans, "rag.answer"), "rag.generation.called")).isEqualTo("false");
        verify(generation, never()).generate(any()); clean(spans);
    }
    @Test void failuresAreSanitizedAndLaterStagesNotExecuted() {
        for (String failure : List.of("embedding", "vector", "database", "generation", "citation")) {
            reset(embedding, generation, repository); setup();
            String stage, code;
            switch (failure) {
                case "embedding" -> { when(embedding.embed(anyList())).thenThrow(new EmbeddingProviderException(new RuntimeException("SECRET_KEY"))); stage="rag.query_embedding"; code="EMBEDDING_PROVIDER_FAILED"; }
                case "vector" -> { when(embedding.embed(anyList())).thenReturn(List.of(new float[2])); stage="rag.query_embedding"; code="EMBEDDING_INVALID_RESPONSE"; }
                case "database" -> { when(repository.searchSimilar(any(), anyString(), anyString(), anyInt(), anyInt(), anyDouble())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("SECRET_SQL")); stage="rag.vector_search"; code="RETRIEVAL_FAILED"; }
                case "generation" -> { when(generation.generate(any())).thenThrow(new GenerationProviderException(new RuntimeException("SECRET_KEY"))); stage="rag.generation"; code="GENERATION_PROVIDER_FAILED"; }
                default -> { when(generation.generate(any())).thenReturn(new GenerationResult("SECRET_ANSWER [S9]", null, null)); stage="rag.citation_validation"; code="GENERATION_INVALID_RESPONSE"; }
            }
            var response = answer(); assertThat(response.getStatusCode().value()).isEqualTo(failure.equals("database") ? 500 : 502);
            var spans = spans(response);
            assertThat(tag(named(spans, stage), "rag.error.code")).isEqualTo(code);
            assertThat(named(spans, stage).getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(tag(named(spans, "rag.answer"), "rag.error.code")).isEqualTo(code);
            if (failure.equals("embedding") || failure.equals("vector")) assertThat(spans).noneMatch(s -> s.getName().equals("rag.vector_search"));
            if (!failure.equals("citation")) assertThat(spans).noneMatch(s -> s.getName().equals("rag.citation_validation"));
            clean(spans);
        }
    }
    @Test void searchAndDisabledAndMalformedRequestsHaveServerTraceHeader() {
        var response = http.postForEntity("/api/v1/search", Map.of("query", "SECRET_QUESTION"), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(spans(response).stream().filter(s -> s.getName().startsWith("rag."))).hasSize(3);
        when(embedding.isEnabled()).thenReturn(false);
        response = http.postForEntity("/api/v1/search", Map.of("query", "q"), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(spans(response).stream().filter(s -> s.getName().startsWith("rag."))).hasSize(1);
        response = http.postForEntity("/api/v1/search", Map.of("unknown", "q"), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400); spans(response);
    }
    @Test @EnabledIfSystemProperty(named="observability.live", matches="true")
    void collectorTempoReceivesAnswerAndNoEvidenceTraces() throws Exception {
        for (boolean empty : List.of(false, true)) {
            if (empty) when(repository.searchSimilar(any(), anyString(), anyString(), anyInt(), anyInt(), anyDouble())).thenReturn(List.of());
            var response = answer(); spans(response);
            String id = response.getHeaders().getFirst("X-Trace-Id");
            String body = "";
            var client = java.net.http.HttpClient.newHttpClient();
            for (int i=0; i<20; i++) {
                var result = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:3200/api/traces/"+id)).header("Accept", "application/json").GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                if (result.statusCode()==200) { body=result.body(); break; }
                Thread.sleep(500);
            }
            assertThat(body).contains("rag.answer", "rag.retrieval", "rag.query_embedding", "rag.vector_search").doesNotContain("SECRET_");
            if (empty) assertThat(body).contains("no_evidence").doesNotContain("rag.generation\"");
            else assertThat(body).contains("rag.context_build", "rag.generation", "rag.citation_validation");
            System.out.println("Tempo verified " + (empty ? "no_evidence" : "answer") + " traceId=" + id);
        }
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class Config {
        @Bean SpanExporter spanExporter() {
            if (Boolean.getBoolean("observability.live")) return SpanExporter.composite(MEMORY,
                    OtlpHttpSpanExporter.builder().setEndpoint("http://localhost:4318/v1/traces").build());
            // Intentionally unreachable collector: requests must still succeed.
            return SpanExporter.composite(MEMORY, OtlpHttpSpanExporter.builder()
                    .setEndpoint("http://127.0.0.1:1/v1/traces").setTimeout(java.time.Duration.ofMillis(100)).build());
        }
    }
}
