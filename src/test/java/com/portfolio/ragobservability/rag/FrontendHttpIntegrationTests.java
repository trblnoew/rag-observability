package com.portfolio.ragobservability.rag;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import com.portfolio.ragobservability.RagObservabilityApplication;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit opt-in is FREE: all AI gateways are Fake and the DB is disposable. */
@EnabledIfSystemProperty(named="frontend.e2e",matches="true")
class FrontendHttpIntegrationTests {
 @Test void browserUsesActualHttpAndIsolatedDatabase() throws Exception {
  try(var db=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-trixie").asCompatibleSubstituteFor("postgres"))){
   db.start();
   try(var context=new SpringApplicationBuilder(RagObservabilityApplication.class,RagApiIntegrationTests.TestConfig.class).run(
     "--spring.config.import=", "--server.port=0", "--server.address=127.0.0.1",
     "--spring.datasource.url="+db.getJdbcUrl(),"--spring.datasource.username="+db.getUsername(),"--spring.datasource.password="+db.getPassword(),
     "--spring.ai.model.embedding=none","--spring.ai.model.chat=none","--rag.embedding.provider=none","--rag.generation.provider=none",
     "--rag.embedding.api-key=","--rag.generation.api-key=","--management.tracing.enabled=false")){
    int port=((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)context).getWebServer().getPort();
    var browserLog=Path.of("target/frontend-browser.log");
    var builder=new ProcessBuilder("node","node_modules/@playwright/test/cli.js","test","--config=playwright.live.config.ts").directory(Path.of("frontend").toFile()).redirectErrorStream(true).redirectOutput(browserLog.toFile());
    builder.environment().put("FRONTEND_TEST_BACKEND_PORT",Integer.toString(port));
    Process process=builder.start();
    try {assertThat(process.waitFor(180,TimeUnit.SECONDS)).as("browser process completed").isTrue();assertThat(process.exitValue()).as("See target/frontend-browser.log and frontend/test-results-live").isZero();}
    finally {if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroy);process.destroyForcibly();}}
   }
  }
 }
}
