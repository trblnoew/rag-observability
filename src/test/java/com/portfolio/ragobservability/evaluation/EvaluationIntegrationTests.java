package com.portfolio.ragobservability.evaluation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.portfolio.ragobservability.RagObservabilityApplication;
import com.portfolio.ragobservability.common.vector.PgVectorFormatter;
import com.portfolio.ragobservability.document.embedding.EmbeddingGateway;
import com.portfolio.ragobservability.retrieval.RetrievalService;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = {RagObservabilityApplication.class, EvaluationIntegrationTests.Config.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"rag.embedding.provider=none", "rag.generation.provider=none", "spring.ai.model.chat=none",
                "spring.ai.model.embedding=none", "management.tracing.enabled=false"})
class EvaluationIntegrationTests {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie").asCompatibleSubstituteFor("postgres"));
    @Autowired JdbcClient jdbc;
    @Autowired RetrievalService retrieval;
    @Autowired FakeGateway gateway;
    @Autowired ConfigurableApplicationContext context;
    @TempDir Path temp;

    @BeforeEach void seed() {
        jdbc.sql("TRUNCATE document_chunk, document RESTART IDENTITY CASCADE").update();
        gateway.calls.set(0);
        int i = 1;
        for (String name : GoldenDataset.DOCUMENTS.stream().sorted().toList()) insert(name, i++);
    }
    private void insert(String filename, int number) {
        long id = jdbc.sql("""
                INSERT INTO document(title, original_filename, source_type, content_sha256, status, raw_content, embedding_status)
                VALUES ('sample', :name, 'MARKDOWN', :sha, 'READY', 'test content', 'READY') RETURNING id
                """).param("name", filename).param("sha", "%064d".formatted(number)).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO document_chunk(document_id, chunk_index, content, token_count, embedding, embedding_provider, embedding_model)
                VALUES (:id, 0, 'SECRET_TEST_CHUNK', 5, CAST(:vector AS vector), 'fake', 'fake-1536')
                """).param("id", id).param("vector", PgVectorFormatter.format(vector())).update();
    }
    @Test void fakeEndToEndProducesCompleteReportWithoutChangingCorpus() throws Exception {
        int code = new EvaluationRunner(retrieval, gateway, new EvaluationCorpus(jdbc), new EvaluationReport())
                .run(Path.of("evaluation/retrieval-golden-set.json"), temp, "test-commit", "false");
        assertThat(code).isZero(); assertThat(gateway.calls.get()).isEqualTo(15);
        Path dir; try (var paths = Files.list(temp)) { dir = paths.findFirst().orElseThrow(); }
        String summary = Files.readString(dir.resolve("summary.md"));
        assertThat(summary).contains("COMPLETE", "Hit@5: 1.0000", "MRR@5: 0.6111", "test-commit", "fake-1536").doesNotContain("SECRET_TEST_CHUNK");
        assertThat(Files.readAllLines(dir.resolve("queries.csv"))).hasSize(16);
        assertThat(Files.readAllLines(dir.resolve("hits.csv"))).hasSize(46);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM document_chunk WHERE embedding IS NOT NULL").query(Long.class).single()).isEqualTo(3);
    }
    @Test void preflightRejectsMissingDuplicateAndUnreadyDocuments() {
        jdbc.sql("UPDATE document SET status='PENDING' WHERE id=1").update();
        assertThatThrownBy(() -> new EvaluationCorpus(jdbc).validate(gateway)).hasMessageContaining("CORPUS_NOT_READY");
        jdbc.sql("UPDATE document SET status='READY'").update();
        insert("db-connection-timeout.md", 9);
        assertThatThrownBy(() -> new EvaluationCorpus(jdbc).validate(gateway)).hasMessageContaining("DUPLICATE");
        jdbc.sql("DELETE FROM document WHERE original_filename='db-connection-timeout.md'").update();
        assertThatThrownBy(() -> new EvaluationCorpus(jdbc).validate(gateway)).hasMessageContaining("MISSING");
        assertThat(gateway.calls.get()).isZero();
    }
    @Test void validationDatasetRuns18CallsAndCompares72RowsWithoutMoreCalls() throws Exception {
        Path input = Path.of("evaluation/retrieval-validation-set-v1.json");
        var data = GoldenDataset.load(input);
        data.documents().forEach((name, hash) -> jdbc.sql("UPDATE document SET content_sha256=:hash WHERE original_filename=:name")
                .param("hash", hash).param("name", name).update());
        assertThat(new EvaluationRunner(retrieval, gateway, new EvaluationCorpus(jdbc), new EvaluationReport())
                .run(input, temp, "a".repeat(40), "false")).isZero();
        Path dir; try (var paths = Files.list(temp)) { dir = paths.findFirst().orElseThrow(); }
        var baseline = com.portfolio.ragobservability.evaluation.threshold.ThresholdBaseline.load(dir);
        Path topk = new com.portfolio.ragobservability.evaluation.topk.TopKReport().write(temp.resolve("topk"), baseline, "unavailable", "unknown");
        assertThat(Files.readAllLines(topk.resolve("query-comparison.csv"))).hasSize(73);
        assertThat(com.portfolio.ragobservability.evaluation.topk.TopKComparisonMain.run(new String[]{dir.toString(), temp.resolve("cli").toString(), "unavailable", "unknown"})).isZero();
        var comparisons = com.portfolio.ragobservability.evaluation.threshold.ThresholdCalculator.compare(baseline,
                com.portfolio.ragobservability.evaluation.threshold.ThresholdCalculator.parseThresholds("0.3,0.4,0.5"));
        assertThat(comparisons).hasSize(4);
        for (var c : comparisons) {
            assertThat(c.relatedCount()).isEqualTo(12);
            assertThat(c.unrelatedCount()).isEqualTo(6);
            assertThat(c.count("DIRECT")).isEqualTo(6);
            assertThat(c.count("PARAPHRASED")).isEqualTo(6);
            assertThat(c.count("HARD_NEGATIVE")).isEqualTo(3);
            assertThat(c.count("UNRELATED")).isEqualTo(3);
            assertThat(c.hitRate()).isEqualTo(1.0);
            assertThat(c.returnRate("HARD_NEGATIVE")).isEqualTo(1.0);
            assertThat(c.scores().stream().filter(s -> s.category().equals("HARD_NEGATIVE"))).allSatisfy(s -> {
                assertThat(s.hit()).isNull(); assertThat(s.rr()).isNull();
            });
        }
        Path report = new com.portfolio.ragobservability.evaluation.threshold.ThresholdReport()
                .write(temp.resolve("comparison"), baseline, comparisons, "abc123", "false");
        assertThat(Files.readAllLines(report.resolve("query-comparison.csv"))).hasSize(73);
        assertThat(Files.readAllLines(dir.resolve("queries.csv"))).hasSize(19);
        assertThat(gateway.calls.get()).isEqualTo(18);
        String manifest = Files.readString(dir.resolve("run.json"));
        assertThat(manifest).contains("judgmentReason", "HARD_NEGATIVE").doesNotContain("SECRET_TEST_CHUNK");
        Files.writeString(dir.resolve("run.json"), "{}");
        assertThat(com.portfolio.ragobservability.evaluation.topk.TopKComparisonMain.run(new String[]{dir.toString(), temp.resolve("invalid").toString(), "unavailable", "unknown"})).isEqualTo(1);
        assertThat(temp.resolve("invalid")).doesNotExist();
        assertThatThrownBy(() -> com.portfolio.ragobservability.evaluation.threshold.ThresholdBaseline.load(dir))
                .hasMessageContaining("INVALID_RUN_MANIFEST");
        Files.writeString(dir.resolve("run.json"), manifest);
        Files.writeString(dir.resolve("hits.csv"), Files.readString(dir.resolve("hits.csv")) + "\n");
        assertThatThrownBy(() -> com.portfolio.ragobservability.evaluation.threshold.ThresholdBaseline.load(dir))
                .hasMessageContaining("INVALID_RUN_MANIFEST");
    }
    @Test void validationCorpusHashMismatchPreventsPaidCalls() {
        assertThat(new EvaluationRunner(retrieval, gateway, new EvaluationCorpus(jdbc), new EvaluationReport())
                .run(Path.of("evaluation/retrieval-validation-set-v1.json"), temp, "abc", "false")).isEqualTo(1);
        assertThat(gateway.calls.get()).isZero();
    }
    @Test void preflightRejectsNullMismatchedVectorsAndExtraEligibleDocuments() {
        jdbc.sql("UPDATE document_chunk SET embedding_model='old' WHERE document_id=1").update();
        assertThatThrownBy(() -> new EvaluationCorpus(jdbc).validate(gateway)).hasMessageContaining("MODEL_MISMATCH");
        jdbc.sql("UPDATE document_chunk SET embedding_model='fake-1536'").update();
        insert("extra.md", 8);
        assertThatThrownBy(() -> new EvaluationCorpus(jdbc).validate(gateway)).hasMessageContaining("EXTRA_ELIGIBLE");
        jdbc.sql("DELETE FROM document WHERE original_filename='extra.md'").update();
        jdbc.sql("UPDATE document_chunk SET embedding=NULL WHERE document_id=1").update();
        assertThatThrownBy(() -> new EvaluationCorpus(jdbc).validate(gateway)).hasMessageContaining("MODEL_MISMATCH");
    }
    @Test void normalStartupDoesNotEvaluateAndCliRequiresLive() {
        assertThat(gateway.calls.get()).isZero();
        assertThat(EvaluationCli.execute(context)).isEqualTo(1);
        assertThat(gateway.calls.get()).isZero();
    }
    @Test void preflightRejectsUnreadyEmbeddingAndEmptyChunks() {
        jdbc.sql("UPDATE document SET embedding_status='PENDING' WHERE id=1").update();
        assertThatThrownBy(() -> new EvaluationCorpus(jdbc).validate(gateway)).hasMessageContaining("CORPUS_NOT_READY");
        jdbc.sql("UPDATE document SET embedding_status='READY'").update();
        jdbc.sql("DELETE FROM document_chunk WHERE document_id=1").update();
        assertThatThrownBy(() -> new EvaluationCorpus(jdbc).validate(gateway)).hasMessageContaining("CORPUS_NOT_READY");
        assertThat(gateway.calls.get()).isZero();
    }
    private static float[] vector() { float[] v = new float[1536]; v[0] = 1; return v; }
    @TestConfiguration(proxyBeanMethods = false)
    static class Config { @Bean @Primary FakeGateway fakeGateway() { return new FakeGateway(); } }
    static class FakeGateway implements EmbeddingGateway {
        final AtomicInteger calls = new AtomicInteger();
        public boolean isEnabled() { return true; }
        public String provider() { return "fake"; }
        public String model() { return "fake-1536"; }
        public int dimensions() { return 1536; }
        public List<float[]> embed(List<String> inputs) { calls.incrementAndGet(); return inputs.stream().map(s -> vector()).toList(); }
    }
}
