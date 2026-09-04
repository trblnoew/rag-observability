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

    public RagService(
            RetrievalService retrievalService,
            GenerationGateway generationGateway,
            ContextBuilder contextBuilder,
            RagPromptBuilder promptBuilder,
            CitationValidator citationValidator,
            AnswerProperties answerProperties) {
        this.retrievalService = retrievalService;
        this.generationGateway = generationGateway;
        this.contextBuilder = contextBuilder;
        this.promptBuilder = promptBuilder;
        this.citationValidator = citationValidator;
        this.answerProperties = answerProperties;
    }

    public RagResult answer(
            String question,
            Integer requestedTopK,
            Double requestedMinimumSimilarity) {
        String normalizedQuestion = normalizeQuestion(question);
        validateTopK(requestedTopK);
        double minimumSimilarity = resolveMinimumSimilarity(requestedMinimumSimilarity);
        ensureGenerationEnabled();

        RetrievalResult retrieval = retrievalService.search(
                normalizedQuestion,
                requestedTopK,
                minimumSimilarity);
        if (retrieval.results().isEmpty()) {
            return result(
                    retrieval,
                    NO_EVIDENCE_ANSWER,
                    java.util.List.of(),
                    false,
                    null,
                    null);
        }

        try {
            BuiltContext context = contextBuilder.build(retrieval.results());
            GenerationResult generated = generationGateway.generate(
                    promptBuilder.build(normalizedQuestion, context));
            if (generated == null) {
                throw new GenerationValidationException("Generation response must not be null");
            }
            String answer = citationValidator.validate(
                    generated.answer(),
                    context.sources().size());
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
