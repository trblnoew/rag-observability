package com.portfolio.ragobservability.document.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.portfolio.ragobservability.document.DocumentException;
import com.portfolio.ragobservability.document.DocumentRepository;
import com.portfolio.ragobservability.document.DocumentSourceType;
import com.portfolio.ragobservability.document.DocumentStatus;
import com.portfolio.ragobservability.document.StoredDocument;

@ExtendWith(MockitoExtension.class)
class DocumentProcessingServiceTests {

    @Mock
    private DocumentChunker documentChunker;

    @Mock
    private DocumentProcessingTransactions transactions;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentChunkRepository documentChunkRepository;

    private DocumentProcessingService service;

    @BeforeEach
    void setUp() {
        service = new DocumentProcessingService(
                documentChunker,
                new ChunkingProperties(600, 2),
                transactions,
                documentRepository,
                documentChunkRepository);
    }

    @Test
    void completesValidChunks() {
        StoredDocument document = processingDocument(0);
        List<ChunkDraft> chunks = List.of(
                new ChunkDraft("first", 1),
                new ChunkDraft("second", 1));
        when(transactions.claim(1L)).thenReturn(document);
        when(documentChunker.split(document.rawContent())).thenReturn(chunks);

        var response = service.process(1L);

        assertThat(response.status()).isEqualTo(DocumentStatus.READY);
        assertThat(response.chunkCount()).isEqualTo(2);
        verify(transactions).complete(1L, chunks);
    }

    @Test
    void marksEmptyResultAsFailed() {
        StoredDocument document = processingDocument(0);
        when(transactions.claim(1L)).thenReturn(document);
        when(documentChunker.split(document.rawContent())).thenReturn(List.of());

        assertThatThrownBy(() -> service.process(1L))
                .isInstanceOfSatisfying(DocumentException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(exception.code()).isEqualTo("CHUNKING_EMPTY_RESULT");
                });
        verify(transactions).fail(
                1L,
                "CHUNKING_EMPTY_RESULT",
                "Document chunking produced no valid chunks");
    }

    @Test
    void marksChunkLimitAsFailed() {
        StoredDocument document = processingDocument(0);
        List<ChunkDraft> chunks = List.of(
                new ChunkDraft("one", 1),
                new ChunkDraft("two", 1),
                new ChunkDraft("three", 1));
        when(transactions.claim(1L)).thenReturn(document);
        when(documentChunker.split(document.rawContent())).thenReturn(chunks);

        assertThatThrownBy(() -> service.process(1L))
                .isInstanceOfSatisfying(DocumentException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(exception.code()).isEqualTo("CHUNK_LIMIT_EXCEEDED");
                });
        verify(transactions).fail(
                1L,
                "CHUNK_LIMIT_EXCEEDED",
                "Document chunk count exceeds the configured limit of 2");
    }

    @Test
    void mapsUnexpectedSplitterFailureWithoutPersistingRawContentInError() {
        StoredDocument document = processingDocument(0);
        when(transactions.claim(1L)).thenReturn(document);
        when(documentChunker.split(document.rawContent()))
                .thenThrow(new IllegalStateException("provider detail"));

        assertThatThrownBy(() -> service.process(1L))
                .isInstanceOfSatisfying(DocumentException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(exception.code()).isEqualTo("CHUNKING_FAILED");
                    assertThat(exception.getMessage()).doesNotContain(document.rawContent());
                });
        verify(transactions).fail(
                1L,
                "CHUNKING_FAILED",
                "Document chunking failed unexpectedly");
    }

    private static StoredDocument processingDocument(int retryCount) {
        Instant now = Instant.now();
        return new StoredDocument(
                1L,
                "guide",
                "guide.txt",
                DocumentSourceType.TXT,
                "a".repeat(64),
                DocumentStatus.PROCESSING,
                null,
                null,
                retryCount,
                "raw document content",
                now,
                now);
    }
}
