package com.portfolio.ragobservability.observability;

import java.util.Set;
import java.util.function.Supplier;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataAccessException;
import com.portfolio.ragobservability.retrieval.RetrievalException;
import com.portfolio.ragobservability.rag.*;
import com.portfolio.ragobservability.document.embedding.*;

@Component
public class RagTracing {
    private final Tracer tracer;
    private static final Set<String> CODES = Set.of("VALIDATION_FAILED", "EMBEDDING_DISABLED",
            "EMBEDDING_PROVIDER_FAILED", "EMBEDDING_INVALID_RESPONSE", "RETRIEVAL_FAILED",
            "GENERATION_DISABLED", "GENERATION_PROVIDER_FAILED", "GENERATION_INVALID_RESPONSE", "RAG_FAILED");

    @Autowired
    public RagTracing(ObjectProvider<Tracer> provider, Environment env) {
        this(env.getProperty("management.tracing.enabled", Boolean.class, true) ? provider.getIfAvailable() : null);
    }
    public RagTracing(Tracer tracer) { this.tracer = tracer; }

    public <T> T trace(String name, Supplier<T> work) {
        if (tracer == null) return work.get();
        Span span = tracer.nextSpan().name(name).start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            tag("rag.outcome", "success");
            return work.get();
        } catch (RuntimeException | Error e) {
            String code = code(e);
            span.tag("rag.outcome", "error").tag("rag.error.code", code);
            span.error(new SafeTraceError(code));
            throw e;
        } finally { span.end(); }
    }
    public void tag(String key, Object value) {
        if (tracer != null && value != null && tracer.currentSpan() != null)
            tracer.currentSpan().tag(key, value.toString());
    }
    public String currentTraceId() {
        Span span = tracer == null ? null : tracer.currentSpan();
        return span == null ? null : span.context().traceId();
    }
    private static String code(Throwable e) {
        String code = e instanceof RetrievalException r ? r.code() : e instanceof RagException r ? r.code() : null;
        if (code != null) return CODES.contains(code) ? code : "INTERNAL_ERROR";
        if (e instanceof EmbeddingProviderException) return "EMBEDDING_PROVIDER_FAILED";
        if (e instanceof EmbeddingValidationException) return "EMBEDDING_INVALID_RESPONSE";
        if (e instanceof GenerationProviderException) return "GENERATION_PROVIDER_FAILED";
        if (e instanceof GenerationValidationException) return "GENERATION_INVALID_RESPONSE";
        if (e instanceof DataAccessException) return "RETRIEVAL_FAILED";
        return "INTERNAL_ERROR";
    }
    private static final class SafeTraceError extends RuntimeException {
        SafeTraceError(String code) { super(code, null, false, false); }
    }
}
