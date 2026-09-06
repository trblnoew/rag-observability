package com.portfolio.ragobservability.evaluation.chunksize;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.portfolio.ragobservability.evaluation.GoldenDataset;

@Testcontainers(disabledWithoutDocker=true)
class ChunkSizeIntegrationTests {
    @TempDir Path temp;
    @Test void realServicesUseThreeIsolatedDatabasesAnd54ReplaySearches() throws Exception {
        var fake=new ChunkSizeUnitTests.Fake();
        String oldUrl=System.getProperty("spring.datasource.url");
        String oldFlyway=System.getProperty("spring.flyway.url");
        try {
            System.setProperty("spring.datasource.url","jdbc:postgresql://127.0.0.1:1/must_not_connect");
            System.setProperty("spring.flyway.url","jdbc:postgresql://127.0.0.1:1/must_not_connect");
            assertThat(ChunkSizeExperimentMain.run(ChunkSizePlan.load(),temp,fake,"unavailable","unknown")).isZero();
        } finally {
            if(oldUrl==null) System.clearProperty("spring.datasource.url"); else System.setProperty("spring.datasource.url",oldUrl);
            if(oldFlyway==null) System.clearProperty("spring.flyway.url"); else System.setProperty("spring.flyway.url",oldFlyway);
        }
        Path out;try(var paths=Files.list(temp)){out=paths.findFirst().orElseThrow();}
        var run=GoldenDataset.mapper().readTree(Files.readString(out.resolve("run.json")));
        assertThat(run.get("complete").asBoolean()).isTrue();
        assertThat(run.get("queryAttempts").asInt()).isEqualTo(18);
        assertThat(run.get("documentAttempts").asInt()).isEqualTo(9);
        assertThat(run.get("replaySearchCalls").asInt()).isEqualTo(54);
        assertThat(fake.calls).isEqualTo(27);
        assertThat(Files.readAllLines(out.resolve("queries.csv"))).hasSize(55);
        assertThat(Files.readAllLines(out.resolve("comparison.csv"))).hasSize(4);
        assertThat(Files.readAllLines(out.resolve("chunks.csv"))).hasSize(48);
        assertThat(run.get("containers")).hasSize(3);
        assertThat(run.get("completedSizes")).hasSize(3);
        for(var id:run.get("containers")) assertThat(org.testcontainers.DockerClientFactory.instance().client().listContainersCmd().withShowAll(true).exec()
                .stream().noneMatch(c -> c.getId().equals(id.asText()))).isTrue();
    }
    @Test void failureStopsPaidCallsAndRemovesContainerWithoutPublishingComparison() throws Exception {
        var fake=new ChunkSizeUnitTests.Fake();fake.failAt=20;
        assertThat(ChunkSizeExperimentMain.run(ChunkSizePlan.load(),temp,fake,"unavailable","unknown")).isEqualTo(1);
        assertThat(fake.calls).isEqualTo(20);
        Path out;try(var paths=Files.list(temp)){out=paths.findFirst().orElseThrow();}
        var run=GoldenDataset.mapper().readTree(Files.readString(out.resolve("run.json")));
        assertThat(run.get("complete").asBoolean()).isFalse();
        assertThat(run.get("containers")).hasSize(1);
        assertThat(run.get("documentAttempts").asInt()).isEqualTo(2);
        assertThat(run.get("documentSucceeded").asInt()).isEqualTo(1);
        assertThat(run.get("failureCode").asText()).isEqualTo("EMBEDDING_PROVIDER_FAILED");
        assertThat(Files.readString(out.resolve("summary.md"))).contains("INCOMPLETE").doesNotContain("SECRET_PROVIDER_DETAIL");
        assertThat(Files.readAllLines(out.resolve("comparison.csv"))).hasSize(1);
        for(var id:run.get("containers")) assertThat(org.testcontainers.DockerClientFactory.instance().client().listContainersCmd().withShowAll(true).exec()
                .stream().noneMatch(c -> c.getId().equals(id.asText()))).isTrue();
    }
}
