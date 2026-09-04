package com.portfolio.ragobservability.document.chunk;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.portfolio.ragobservability.document.DocumentException;
import com.portfolio.ragobservability.document.DocumentRepository;
import com.portfolio.ragobservability.document.StoredDocument;

@Service
public class DocumentProcessingTransactions {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;

    public DocumentProcessingTransactions(
            DocumentRepository documentRepository,
            DocumentChunkRepository documentChunkRepository) {
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StoredDocument claim(long documentId) {
        return documentRepository.claimForProcessing(documentId)
                .orElseGet(() -> {
                    if (documentRepository.findById(documentId).isEmpty()) {
                        throw notFound(documentId);
                    }
                    throw new DocumentException(
                            HttpStatus.CONFLICT,
                            "DOCUMENT_NOT_PROCESSABLE",
                            "Only PENDING or FAILED documents can be processed");
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(long documentId, java.util.List<ChunkDraft> chunks) {
        documentChunkRepository.replace(documentId, chunks);
        if (!documentRepository.markReady(documentId)) {
            throw new DocumentException(
                    HttpStatus.CONFLICT,
                    "DOCUMENT_NOT_PROCESSABLE",
                    "Document is no longer in PROCESSING status");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(long documentId, String errorCode, String errorMessage) {
        documentChunkRepository.deleteByDocumentId(documentId);
        documentRepository.markFailed(documentId, errorCode, errorMessage);
    }

    private static DocumentException notFound(long documentId) {
        return new DocumentException(
                HttpStatus.NOT_FOUND,
                "DOCUMENT_NOT_FOUND",
                "Document %d was not found".formatted(documentId));
    }
}
