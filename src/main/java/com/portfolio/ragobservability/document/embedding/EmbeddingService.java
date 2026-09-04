package com.portfolio.ragobservability.document.embedding;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.portfolio.ragobservability.document.DocumentException;
import com.portfolio.ragobservability.document.embedding.dto.EmbeddingProcessingResponse;
import com.portfolio.ragobservability.document.embedding.dto.EmbeddingStatusResponse;

@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingGateway embeddingGateway;
    private final EmbeddingProperties properties;
    private final EmbeddingTransactions transactions;
    private final DocumentEmbeddingRepository embeddingRepository;
    private final EmbeddingVectorValidator vectorValidator;

    public EmbeddingService(
            EmbeddingGateway embeddingGateway,
            EmbeddingProperties properties,
            EmbeddingTransactions transactions,
            DocumentEmbeddingRepository embeddingRepository,
            EmbeddingVectorValidator vectorValidator) {
        this.embeddingGateway = embeddingGateway;
        this.properties = properties;
        this.transactions = transactions;
        this.embeddingRepository = embeddingRepository;
        this.vectorValidator = vectorValidator;
    }

    public EmbeddingProcessingResponse process(long documentId) {
        if (!embeddingRepository.existsDocument(documentId)) {
            throw notFound(documentId);
        }
        ensureEnabled();
        EmbeddingClaim claim = transactions.claim(documentId);

        try {
            List<EmbeddableChunk> chunks = embeddingRepository.findChunks(documentId);
            if (chunks.isEmpty()) {
                throw invalidResponse("Document has no chunks to embed");
            }

            List<ChunkEmbedding> embeddings = embedAll(chunks);
            transactions.complete(
                    documentId,
                    embeddings,
                    embeddingGateway.provider(),
                    embeddingGateway.model());
            return new EmbeddingProcessingResponse(
                    documentId,
                    EmbeddingStatus.READY,
                    embeddings.size(),
                    embeddingGateway.provider(),
                    embeddingGateway.model(),
                    embeddingGateway.dimensions(),
                    claim.retryCount());
        } catch (EmbeddingProviderException exception) {
            return failAndThrow(
                    documentId,
                    HttpStatus.BAD_GATEWAY,
                    "EMBEDDING_PROVIDER_FAILED",
                    "Embedding provider request failed",
                    exception);
        } catch (DocumentException exception) {
            fail(documentId, exception.code(), exception.getMessage(), exception);
            throw exception;
        } catch (RuntimeException exception) {
            return failAndThrow(
                    documentId,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "EMBEDDING_PERSISTENCE_FAILED",
                    "Document embeddings could not be persisted",
                    exception);
        }
    }

    public EmbeddingStatusResponse getStatus(long documentId) {
        EmbeddingStatusSnapshot status = embeddingRepository.findStatus(documentId)
                .orElseThrow(() -> notFound(documentId));

        return new EmbeddingStatusResponse(
                documentId,
                status.status(),
                status.totalChunkCount(),
                status.embeddedChunkCount(),
                status.provider(),
                status.model(),
                status.dimensions(),
                status.retryCount(),
                status.errorCode(),
                status.errorMessage());
    }

    private List<ChunkEmbedding> embedAll(List<EmbeddableChunk> chunks) {
        List<ChunkEmbedding> embeddings = new ArrayList<>(chunks.size());
        int batchSize = properties.getBatchSize();
        for (int from = 0; from < chunks.size(); from += batchSize) {
            int to = Math.min(from + batchSize, chunks.size());
            List<EmbeddableChunk> batch = chunks.subList(from, to);
            List<String> inputs = batch.stream().map(EmbeddableChunk::content).toList();
            try {
                List<float[]> vectors = vectorValidator.validateResults(
                        embeddingGateway.embed(inputs),
                        batch.size(),
                        embeddingGateway.dimensions());
                for (int index = 0; index < vectors.size(); index++) {
                    embeddings.add(new ChunkEmbedding(batch.get(index).id(), vectors.get(index)));
                }
            } catch (EmbeddingValidationException exception) {
                throw invalidResponse(exception.getMessage());
            }
        }
        return embeddings;
    }

    private void ensureEnabled() {
        if (!embeddingGateway.isEnabled()) {
            throw new DocumentException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "EMBEDDING_DISABLED",
                    "Embedding provider is disabled");
        }
    }

    private EmbeddingProcessingResponse failAndThrow(
            long documentId,
            HttpStatus status,
            String code,
            String message,
            RuntimeException cause) {
        fail(documentId, code, message, cause);
        throw new DocumentException(status, code, message);
    }

    private void fail(long documentId, String code, String message, RuntimeException cause) {
        log.error("Document embedding failed for document {} with {}",
                documentId, cause.getClass().getSimpleName());
        try {
            transactions.fail(documentId, code, message);
        } catch (RuntimeException failurePersistenceException) {
            log.error("Embedding failure state could not be persisted for document {} with {}",
                    documentId, failurePersistenceException.getClass().getSimpleName());
        }
    }

    private static DocumentException invalidResponse(String message) {
        return new DocumentException(
                HttpStatus.BAD_GATEWAY,
                "EMBEDDING_INVALID_RESPONSE",
                message);
    }

    private static DocumentException notFound(long documentId) {
        return new DocumentException(
                HttpStatus.NOT_FOUND,
                "DOCUMENT_NOT_FOUND",
                "Document %d was not found".formatted(documentId));
    }
}
