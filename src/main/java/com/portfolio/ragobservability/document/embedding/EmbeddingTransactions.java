package com.portfolio.ragobservability.document.embedding;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.portfolio.ragobservability.document.DocumentException;
import com.portfolio.ragobservability.document.DocumentRepository;
import com.portfolio.ragobservability.document.DocumentStatus;
import com.portfolio.ragobservability.document.StoredDocument;

@Service
public class EmbeddingTransactions {

    private final DocumentRepository documentRepository;
    private final DocumentEmbeddingRepository embeddingRepository;

    public EmbeddingTransactions(
            DocumentRepository documentRepository,
            DocumentEmbeddingRepository embeddingRepository) {
        this.documentRepository = documentRepository;
        this.embeddingRepository = embeddingRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EmbeddingClaim claim(long documentId) {
        return embeddingRepository.claim(documentId)
                .map(claim -> {
                    embeddingRepository.clearEmbeddings(documentId);
                    return claim;
                })
                .orElseGet(() -> explainRejectedClaim(documentId));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(
            long documentId,
            List<ChunkEmbedding> embeddings,
            String provider,
            String model) {
        embeddingRepository.saveAll(embeddings, provider, model);
        if (!embeddingRepository.markReady(documentId)) {
            throw new DocumentException(
                    HttpStatus.CONFLICT,
                    "EMBEDDING_NOT_PROCESSABLE",
                    "Document embedding is no longer in PROCESSING status");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(long documentId, String errorCode, String errorMessage) {
        embeddingRepository.clearEmbeddings(documentId);
        embeddingRepository.markFailed(documentId, errorCode, errorMessage);
    }

    private EmbeddingClaim explainRejectedClaim(long documentId) {
        StoredDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentException(
                        HttpStatus.NOT_FOUND,
                        "DOCUMENT_NOT_FOUND",
                        "Document %d was not found".formatted(documentId)));
        if (document.status() != DocumentStatus.READY
                || document.embeddingStatus() == EmbeddingStatus.NOT_STARTED) {
            throw new DocumentException(
                    HttpStatus.CONFLICT,
                    "DOCUMENT_NOT_CHUNKED",
                    "Document chunking must be READY before embedding");
        }
        throw new DocumentException(
                HttpStatus.CONFLICT,
                "EMBEDDING_NOT_PROCESSABLE",
                "Only PENDING or FAILED document embeddings can be processed");
    }
}
