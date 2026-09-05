package com.portfolio.ragobservability.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.flywaydb.core.Flyway;

@ActiveProfiles("evaluation")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "rag.embedding.provider=none", "spring.ai.model.embedding=none", "rag.embedding.api-key=",
        "rag.generation.api-key=", "spring.datasource.url=jdbc:postgresql://localhost:1/unused"})
class EvaluationProfileTests {
    @Autowired ConfigurableApplicationContext context;

    @Test void profileStartsWithoutDatabaseMigrationWebServerOrLiveExecution() {
        assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
        assertThat(context.getEnvironment().getProperty("rag.generation.provider")).isEqualTo("none");
        assertThat(context.getEnvironment().getProperty("management.tracing.enabled")).isEqualTo("false");
        assertThat(context.getEnvironment().getProperty("spring.main.web-application-type")).isEqualTo("none");
        assertThat(EvaluationCli.execute(context)).isEqualTo(1);
        var tracing = context.getBean(com.portfolio.ragobservability.observability.RagTracing.class);
        assertThat(tracing.trace("rag.test", () -> tracing.currentTraceId())).isNull();
    }
}
