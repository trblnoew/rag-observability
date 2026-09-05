package com.portfolio.ragobservability.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.portfolio.ragobservability.retrieval.RetrievalProperties;
import com.portfolio.ragobservability.retrieval.RetrievalResult;
import com.portfolio.ragobservability.retrieval.RetrievalService;

@Service
public class RagService {

    public static final String NO_EVIDENCE_ANSWER = "제공된 문서만으로는 확인할 수 없습니다.";

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final RetrievalService retrievalService;
    private final GenerationGateway generationGateway;
    private final ContextBuilder contextBuilder;
    private final RagPromptBuilder promptBuilder;
    private final CitationValidator citationValidator;
    private final AnswerProperties answerProperties;
    private final com.portfolio.ragobservability.observability.RagTracing tracing;

    public RagService(
            RetrievalService retrievalService,
            GenerationGateway generationGateway,
            ContextBuilder contextBuilder,
            RagPromptBuilder promptBuilder,
            CitationValidator citationValidator,
            AnswerProperties answerProperties) {
        this(retrievalService, generationGateway, contextBuilder, promptBuilder, citationValidator, answerProperties,
                new com.portfolio.ragobservability.observability.RagTracing(null));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RagService(RetrievalService retrievalService, GenerationGateway generationGateway,
            ContextBuilder contextBuilder, RagPromptBuilder promptBuilder, CitationValidator citationValidator,
            AnswerProperties answerProperties, com.portfolio.ragobservability.observability.RagTracing tracing) {
        this.retrievalService = retrievalService;
        this.generationGateway = generationGateway;
        this.contextBuilder = contextBuilder;
        this.promptBuilder = promptBuilder;
        this.citationValidator = citationValidator;
        this.answerProperties = answerProperties;
        this.tracing = tracing;
    }

    public RagResult answer(
            String question,
            Integer requestedTopK,
            Double requestedMinimumSimilarity) {
        return tracing.trace("rag.answer", () -> answerTraced(question, requestedTopK, requestedMinimumSimilarity));
    }

    private RagResult answerTraced(String question, Integer requestedTopK, Double requestedMinimumSimilarity) {
        tracing.tag("rag.generation.called", false);
        String normalizedQuestion = normalizeQuestion(question);
        validateTopK(requestedTopK);
        double minimumSimilarity = resolveMinimumSimilarity(requestedMinimumSimilarity);
        ensureGenerationEnabled();

        RetrievalResult retrieval = retrievalService.search(
                normalizedQuestion,
                requestedTopK,
                minimumSimilarity);
        if (retrieval.results().isEmpty()) {
            tracing.tag("rag.outcome", "no_evidence");
            return result(
                    retrieval,
                    NO_EVIDENCE_ANSWER,
                    java.util.List.of(),
                    false,
                    null,
                    null);
        }

        try {
            record Prepared(BuiltContext context, GenerationPrompt prompt) {}
            Prepared prepared = tracing.trace("rag.context_build", () -> {
                BuiltContext built = contextBuilder.build(retrieval.results());
                tracing.tag("rag.source_count", built.sources().size());
                return new Prepared(built, promptBuilder.build(normalizedQuestion, built));
            });
            BuiltContext context = prepared.context();
            tracing.tag("rag.generation.called", true);
            GenerationResult generated = tracing.trace("rag.generation", () -> {
                tracing.tag("rag.generation.provider", generationGateway.provider());
                tracing.tag("rag.generation.model", generationGateway.model());
                GenerationResult result = generationGateway.generate(prepared.prompt());
                if (result != null) {
                    tracing.tag("rag.generation.input_tokens", result.inputTokens());
                    tracing.tag("rag.generation.output_tokens", result.outputTokens());
                }
                return result;
            });
            String answer = tracing.trace("rag.citation_validation", () -> {
                if (generated == null) throw new GenerationValidationException("Generation response must not be null");
                return citationValidator.validate(
                    generated.answer(),
                    context.sources().size());
            });
            return result(
                    retrieval,
                    answer,
                    context.sources(),
                    true,
                    generated.inputTokens(),
                    generated.outputTokens());
        } catch (GenerationProviderException exception) {
            throw new RagException(
                    HttpStatus.BAD_GATEWAY,
                    "GENERATION_PROVIDER_FAILED",
                    "Generation provider request failed");
        } catch (GenerationValidationException exception) {
            throw new RagException(
                    HttpStatus.BAD_GATEWAY,
                    "GENERATION_INVALID_RESPONSE",
                    exception.getMessage());
        } catch (RuntimeException exception) {
            log.error("RAG answer assembly failed with {}", exception.getClass().getSimpleName());
            throw new RagException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "RAG_FAILED",
                    "RAG answer generation failed");
        }
    }

    private RagResult result(
            RetrievalResult retrieval,
            String answer,
            java.util.List<RagSource> sources,
            boolean generationCalled,
            Integer inputTokens,
            Integer outputTokens) {
        return new RagResult(
                retrieval.query(),
                answer,
                sources,
                retrieval.topK(),
                retrieval.minimumSimilarity(),
                retrieval.embeddingProvider(),
                retrieval.embeddingModel(),
                retrieval.dimensions(),
                generationGateway.provider(),
                generationGateway.model(),
                generationCalled,
                inputTokens,
                outputTokens);
    }

    private static String normalizeQuestion(String question) {
        if (question == null) {
            throw invalidRequest("Question must not be blank");
        }
        String normalized = question.strip();
        if (normalized.isEmpty()) {
            throw invalidRequest("Question must not be blank");
        }
        if (normalized.length() > RetrievalProperties.MAX_QUERY_LENGTH) {
            throw invalidRequest("Question must not exceed 2000 characters");
        }
        return normalized;
    }

    private static void validateTopK(Integer topK) {
        if (topK != null
                && (topK < RetrievalProperties.MIN_TOP_K
                        || topK > RetrievalProperties.MAX_TOP_K)) {
            throw invalidRequest("topK must be between 1 and 20");
        }
    }

    private double resolveMinimumSimilarity(Double requestedMinimumSimilarity) {
        double minimumSimilarity = requestedMinimumSimilarity == null
                ? answerProperties.getMinimumSimilarity()
                : requestedMinimumSimilarity;
        if (!Double.isFinite(minimumSimilarity)
                || minimumSimilarity < -1.0
                || minimumSimilarity > 1.0) {
            throw invalidRequest("minimumSimilarity must be a finite value between -1.0 and 1.0");
        }
        return minimumSimilarity;
    }

    private void ensureGenerationEnabled() {
        if (!generationGateway.isEnabled()) {
            throw new RagException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "GENERATION_DISABLED",
                    "Generation provider is disabled");
        }
    }

    private static RagException invalidRequest(String message) {
        return new RagException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }
}
