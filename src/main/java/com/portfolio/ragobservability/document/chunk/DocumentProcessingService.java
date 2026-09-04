package com.portfolio.ragobservability.document.chunk;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.portfolio.ragobservability.document.DocumentException;
import com.portfolio.ragobservability.document.DocumentRepository;
import com.portfolio.ragobservability.document.DocumentStatus;
import com.portfolio.ragobservability.document.StoredDocument;
import com.portfolio.ragobservability.document.chunk.dto.DocumentChunkResponse;
import com.portfolio.ragobservability.document.chunk.dto.DocumentChunksResponse;
import com.portfolio.ragobservability.document.chunk.dto.DocumentProcessingResponse;

@Service
public class DocumentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingService.class);

    private final DocumentChunker documentChunker;
    private final ChunkingProperties chunkingProperties;
    private final DocumentProcessingTransactions transactions;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;

    public DocumentProcessingService(
            DocumentChunker documentChunker,
            ChunkingProperties chunkingProperties,
            DocumentProcessingTransactions transactions,
            DocumentRepository documentRepository,
            DocumentChunkRepository documentChunkRepository) {
        this.documentChunker = documentChunker;
        this.chunkingProperties = chunkingProperties;
        this.transactions = transactions;
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
    }

    public DocumentProcessingResponse process(long documentId) {
        StoredDocument claimed = transactions.claim(documentId);

        try {
            List<ChunkDraft> chunks = documentChunker.split(claimed.rawContent());
            validateChunks(chunks);
            transactions.complete(documentId, chunks);
            return new DocumentProcessingResponse(
                    documentId,
                    DocumentStatus.READY,
                    chunks.size(),
                    claimed.retryCount());
        } catch (DocumentException exception) {
            transactions.fail(documentId, exception.code(), exception.getMessage());
            throw exception;
        } catch (RuntimeException exception) {
            log.error("Document chunking failed for document {} with {}",
                    documentId, exception.getClass().getSimpleName());
            String errorCode = "CHUNKING_FAILED";
            String errorMessage = "Document chunking failed unexpectedly";
            transactions.fail(documentId, errorCode, errorMessage);
            throw new DocumentException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    errorCode,
                    errorMessage);
        }
    }

    public DocumentChunksResponse findChunks(long documentId) {
        StoredDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentException(
                        HttpStatus.NOT_FOUND,
                        "DOCUMENT_NOT_FOUND",
                        "Document %d was not found".formatted(documentId)));
        List<DocumentChunkResponse> chunks = documentChunkRepository.findByDocumentId(documentId).stream()
                .map(chunk -> new DocumentChunkResponse(
                        chunk.id(),
                        chunk.chunkIndex(),
                        chunk.content(),
                        chunk.tokenCount(),
                        chunk.embedded(),
                        chunk.embeddingProvider(),
                        chunk.embeddingModel(),
                        chunk.createdAt()))
                .toList();
        return new DocumentChunksResponse(documentId, document.status(), chunks);
    }

    private void validateChunks(List<ChunkDraft> chunks) {
        if (chunks == null || chunks.isEmpty()
                || chunks.stream().anyMatch(chunk -> chunk == null
                        || chunk.content() == null
                        || chunk.content().isBlank()
                        || chunk.tokenCount() <= 0)) {
            throw new DocumentException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "CHUNKING_EMPTY_RESULT",
                    "Document chunking produced no valid chunks");
        }
        if (chunks.size() > chunkingProperties.maxChunksPerDocument()) {
            throw new DocumentException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "CHUNK_LIMIT_EXCEEDED",
                    "Document chunk count exceeds the configured limit of %d"
                            .formatted(chunkingProperties.maxChunksPerDocument()));
        }
    }
}
