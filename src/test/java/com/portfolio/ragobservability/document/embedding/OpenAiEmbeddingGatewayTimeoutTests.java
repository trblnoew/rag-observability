package com.portfolio.ragobservability.document.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

class OpenAiEmbeddingGatewayTimeoutTests {

    @Test
    void wrapsReadTimeoutWithoutAutomaticRetry() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicInteger requestCount = new AtomicInteger();
        server.setExecutor(executor);
        server.createContext("/", exchange -> respondAfterDelay(exchange, requestCount));
        server.start();

        try {
            EmbeddingProperties properties = new EmbeddingProperties();
            properties.setProvider("openai");
            properties.setApiKey("test-key");
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setConnectTimeout(Duration.ofSeconds(1));
            properties.setReadTimeout(Duration.ofMillis(100));
            properties.validate();
            EmbeddingGateway gateway = new EmbeddingConfiguration()
                    .openAiEmbeddingGateway(properties);

            long startedAt = System.nanoTime();
            assertThatThrownBy(() -> gateway.embed(List.of("timeout test")))
                    .isInstanceOf(EmbeddingProviderException.class);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
            assertThat(requestCount).hasValue(1);
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static void respondAfterDelay(HttpExchange exchange, AtomicInteger requestCount) {
        requestCount.incrementAndGet();
        try (exchange) {
            Thread.sleep(5_000);
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // The client is expected to close the connection after the read timeout.
        }
    }
}
