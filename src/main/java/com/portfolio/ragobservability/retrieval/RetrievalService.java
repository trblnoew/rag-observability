package com.portfolio.ragobservability.retrieval;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.portfolio.ragobservability.document.embedding.EmbeddingGateway;
import com.portfolio.ragobservability.document.embedding.EmbeddingProviderException;
import com.portfolio.ragobservability.document.embedding.EmbeddingValidationException;
import com.portfolio.ragobservability.document.embedding.EmbeddingVectorValidator;

@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final EmbeddingGateway embeddingGateway;
    private final EmbeddingVectorValidator vectorValidator;
    private final RetrievalRepository retrievalRepository;
    private final RetrievalProperties properties;
    private final com.portfolio.ragobservability.observability.RagTracing tracing;

    public RetrievalService(
            EmbeddingGateway embeddingGateway,
            EmbeddingVectorValidator vectorValidator,
            RetrievalRepository retrievalRepository,
            RetrievalProperties properties) {
        this(embeddingGateway, vectorValidator, retrievalRepository, properties,
                new com.portfolio.ragobservability.observability.RagTracing(null));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RetrievalService(EmbeddingGateway embeddingGateway, EmbeddingVectorValidator vectorValidator,
            RetrievalRepository retrievalRepository, RetrievalProperties properties,
            com.portfolio.ragobservability.observability.RagTracing tracing) {
        this.embeddingGateway = embeddingGateway;
        this.vectorValidator = vectorValidator;
        this.retrievalRepository = retrievalRepository;
        this.properties = properties;
        this.tracing = tracing;
    }

    public RetrievalResult search(
            String query,
            Integer requestedTopK,
            Double requestedMinimumSimilarity) {
        return tracing.trace("rag.retrieval", () -> searchTraced(query, requestedTopK, requestedMinimumSimilarity));
    }

    private RetrievalResult searchTraced(String query, Integer requestedTopK, Double requestedMinimumSimilarity) {
        String normalizedQuery = normalizeQuery(query);
        int topK = resolveTopK(requestedTopK);
        double minimumSimilarity = resolveMinimumSimilarity(requestedMinimumSimilarity);
        tracing.tag("rag.top_k", topK);
        tracing.tag("rag.minimum_similarity", minimumSimilarity);
        ensureEnabled();

        float[] queryEmbedding;
        try {
            List<float[]> results = tracing.trace("rag.query_embedding", () -> {
                tracing.tag("rag.embedding.provider", embeddingGateway.provider());
                tracing.tag("rag.embedding.model", embeddingGateway.model());
                tracing.tag("rag.embedding.dimensions", embeddingGateway.dimensions());
                return vectorValidator.validateResults(
                    embeddingGateway.embed(List.of(normalizedQuery)),
                    1,
                    embeddingGateway.dimensions());
            });
            queryEmbedding = results.getFirst();
        } catch (EmbeddingProviderException exception) {
            throw new RetrievalException(
                    HttpStatus.BAD_GATEWAY,
                    "EMBEDDING_PROVIDER_FAILED",
                    "Embedding provider request failed");
        } catch (EmbeddingValidationException exception) {
            throw new RetrievalException(
                    HttpStatus.BAD_GATEWAY,
                    "EMBEDDING_INVALID_RESPONSE",
                    exception.getMessage());
        }

        try {
            List<SearchHit> hits = tracing.trace("rag.vector_search", () -> {
                List<SearchHit> found = retrievalRepository.searchSimilar(
                    queryEmbedding,
                    embeddingGateway.provider(),
                    embeddingGateway.model(),
                    embeddingGateway.dimensions(),
                    topK,
                    minimumSimilarity);
                tracing.tag("rag.result_count", found.size());
                if (found.isEmpty()) tracing.tag("rag.outcome", "empty");
                return found;
            });
            tracing.tag("rag.result_count", hits.size());
            if (hits.isEmpty()) tracing.tag("rag.outcome", "empty");
            return new RetrievalResult(
                    normalizedQuery,
                    hits,
                    topK,
                    minimumSimilarity,
                    embeddingGateway.provider(),
                    embeddingGateway.model(),
                    embeddingGateway.dimensions());
        } catch (DataAccessException exception) {
            log.error("Retrieval database query failed with {}", exception.getClass().getSimpleName());
            throw new RetrievalException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "RETRIEVAL_FAILED",
                    "Vector retrieval failed");
        }
    }

    private String normalizeQuery(String query) {
        if (query == null) {
            throw invalidRequest("Query must not be blank");
        }
        String normalized = query.strip();
        if (normalized.isEmpty()) {
            throw invalidRequest("Query must not be blank");
        }
        if (normalized.length() > RetrievalProperties.MAX_QUERY_LENGTH) {
            throw invalidRequest("Query must not exceed 2000 characters");
        }
        return normalized;
    }

    private int resolveTopK(Integer requestedTopK) {
        int topK = requestedTopK == null ? properties.getTopK() : requestedTopK;
        if (topK < RetrievalProperties.MIN_TOP_K || topK > RetrievalProperties.MAX_TOP_K) {
            throw invalidRequest("topK must be between 1 and 20");
        }
        return topK;
    }

    private double resolveMinimumSimilarity(Double requestedMinimumSimilarity) {
        double minimumSimilarity = requestedMinimumSimilarity == null
                ? properties.getMinimumSimilarity()
                : requestedMinimumSimilarity;
        if (!Double.isFinite(minimumSimilarity)
                || minimumSimilarity < -1.0
                || minimumSimilarity > 1.0) {
            throw invalidRequest("minimumSimilarity must be a finite value between -1.0 and 1.0");
        }
        return minimumSimilarity;
    }

    private void ensureEnabled() {
        if (!embeddingGateway.isEnabled()) {
            throw new RetrievalException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "EMBEDDING_DISABLED",
                    "Embedding provider is disabled");
        }
    }

    private static RetrievalException invalidRequest(String message) {
        return new RetrievalException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }
}
